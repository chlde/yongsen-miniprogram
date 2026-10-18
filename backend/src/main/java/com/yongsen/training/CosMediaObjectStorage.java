package com.yongsen.training;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** COS SDK adapter. Only bucket/region from trusted deployment configuration are accepted. */
@Component
@ConditionalOnProperty(name = "training.storage.mode", havingValue = "cos")
public class CosMediaObjectStorage implements MediaObjectStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(CosMediaObjectStorage.class);
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,49}-[0-9]{5,20}");
    private static final Pattern REGION =
            Pattern.compile("[a-z]{2,8}-[a-z]+(?:-[a-z]+)?(?:-[0-9]+)?");
    private static final Pattern KEY = Pattern.compile("(?:uploads|materials)/[A-Za-z0-9/._-]+");
    private static final String ARCHIVE_SOURCE_ETAG = "training-source-etag";
    private final COSClient client;
    private final String bucket;

    @Autowired
    public CosMediaObjectStorage(
            @Value("${training.cos.bucket:}") String bucket,
            @Value("${training.cos.region:}") String region,
            CloudCredentials credentials) {
        this(createClient(region, credentials), bucket);
    }

    CosMediaObjectStorage(COSClient client, String bucket) {
        if (!BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("A valid private COS bucket is required");
        }
        this.client = client;
        this.bucket = bucket;
    }

    static COSClient createClient(String region, CloudCredentials source) {
        if (!REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("A valid COS region is required");
        }
        COSCredentialsProvider credentials =
                new COSCredentialsProvider() {
                    @Override
                    public COSCredentials getCredentials() {
                        CloudCredentials.Value value = source.current();
                        return value.sessionToken() == null || value.sessionToken().isBlank()
                                ? new BasicCOSCredentials(value.secretId(), value.secretKey())
                                : new BasicSessionCredentials(
                                        value.secretId(), value.secretKey(), value.sessionToken());
                    }

                    @Override
                    public void refresh() {
                        // Credentials are obtained from deployment-managed private environment
                        // variables.
                    }
                };
        credentials.getCredentials();
        ClientConfig config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(10_000);
        config.setConnectionRequestTimeout(10_000);
        config.setSocketTimeout(30_000);
        config.setMaxErrorRetry(0);
        config.setMaxErrorRetryForCopyRequest(0);
        config.setChangeEndpointRetry(false);
        config.setRetryAfterPreflight(false);
        config.setRequestTimeOutEnable(true);
        config.setRequestTimeout(30_000);
        return new COSClient(credentials, config);
    }

    @Override
    public Upload createUpload(
            String key, String contentType, long expectedBytes, Instant expiresAt) {
        requireKey(key);
        Data.require(key.startsWith("uploads/"), "INVALID_FILE_PATH", "上传位置无效");
        Data.require(expectedBytes > 0, "FILE_TOO_LARGE", "文件大小无效");
        Map<String, String> signedHeaders =
                Map.of(
                        "Content-Type",
                        contentType,
                        "Content-Length",
                        Long.toString(expectedBytes),
                        "x-cos-acl",
                        "default");
        String url =
                client.generatePresignedUrl(
                                bucket,
                                key,
                                Date.from(expiresAt),
                                HttpMethodName.PUT,
                                signedHeaders,
                                Map.of(),
                                true,
                                true)
                        .toString();
        // Browser fetch sets Content-Length from the File body; scripts cannot override it.
        return new Upload(url, "PUT", Map.of("Content-Type", contentType, "x-cos-acl", "default"));
    }

    @Override
    public Metadata stat(String key) {
        return find(key)
                .orElseThrow(() -> new BusinessException("UPLOAD_INCOMPLETE", "文件尚未完整上传", 422));
    }

    @Override
    public Optional<Metadata> find(String key) {
        requireKey(key);
        try {
            ObjectMetadata result = client.getObjectMetadata(bucket, key);
            return Optional.of(
                    new Metadata(
                            result.getContentLength(),
                            result.getETag(),
                            result.getUserMetadata().get(ARCHIVE_SOURCE_ETAG)));
        } catch (CosServiceException exception) {
            String code = exception.getErrorCode();
            // HEAD may have no XML error body. An ambiguous HEAD 404 is only a missing
            // candidate; the following COPY still distinguishes NoSuchBucket from NoSuchKey.
            if (exception.getStatusCode() == 404
                    && (code == null
                            || code.isBlank()
                            || "Not Found".equals(code)
                            || "404 Not Found".equals(code)
                            || "NoSuchKey".equals(code))) {
                return Optional.empty();
            }
            throw unavailable(exception);
        } catch (CosClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void copyIfMatch(String source, String target, String eTag) {
        requireKey(source);
        requireKey(target);
        Data.require(
                source.startsWith("uploads/")
                        && target.startsWith("materials/")
                        && eTag != null
                        && !eTag.isBlank(),
                "INVALID_FILE_PATH",
                "文件归档位置无效");
        CopyObjectRequest request = new CopyObjectRequest(bucket, source, bucket, target);
        request.setMatchingETagConstraints(List.of(eTag));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(target.endsWith(".mp4") ? "video/mp4" : "application/pdf");
        metadata.setCacheControl("private, no-store");
        metadata.addUserMetadata(ARCHIVE_SOURCE_ETAG, eTag);
        request.setNewObjectMetadata(metadata);
        request.setMetadataDirective("REPLACE");
        try {
            client.copyObject(request);
        } catch (CosServiceException exception) {
            if ((exception.getStatusCode() == 412
                            && "PreconditionFailed".equals(exception.getErrorCode()))
                    || (exception.getStatusCode() == 404
                            && ("NoSuchKey".equals(exception.getErrorCode())
                                    || "NoSuchCopySource".equals(exception.getErrorCode())))) {
                throw new BusinessException("UPLOAD_SOURCE_CHANGED", "上传文件在确认后被修改或移除，请重新上传", 422);
            }
            throw unavailable(exception);
        } catch (CosClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void download(String key, Path destination, long expectedBytes) throws IOException {
        requireKey(key);
        try (COSObject object = client.getObject(bucket, key);
                InputStream input = object.getObjectContent();
                OutputStream output = Files.newOutputStream(destination)) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
            long count = 0;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                Data.require(System.nanoTime() < deadline, "MEDIA_PROCESS_TIMEOUT", "文件下载处理超时");
                count += read;
                Data.require(count <= expectedBytes, "UPLOAD_SIZE_MISMATCH", "文件大小与登记不符");
                output.write(buffer, 0, read);
            }
            Data.require(count == expectedBytes, "UPLOAD_SIZE_MISMATCH", "文件不完整");
        } catch (CosClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void put(String key, Path source, String contentType) throws IOException {
        requireKey(key);
        Data.require(key.startsWith("materials/"), "INVALID_FILE_PATH", "文件存储位置无效");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(Files.size(source));
        metadata.setCacheControl("private, no-store");
        PutObjectRequest request = new PutObjectRequest(bucket, key, source.toFile());
        request.setMetadata(metadata);
        try {
            client.putObject(request);
        } catch (CosClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public String signedGet(String key, String contentType, Instant expiresAt) {
        requireKey(key);
        Data.require(key.startsWith("materials/"), "INVALID_FILE_PATH", "文件读取位置无效");
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
        request.setExpiration(Date.from(expiresAt));
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setContentType(contentType);
        headers.setCacheControl("private, no-store");
        request.setResponseHeaders(headers);
        return client.generatePresignedUrl(request).toString();
    }

    @Override
    public void delete(String key) {
        requireKey(key);
        try {
            client.deleteObject(bucket, key);
        } catch (CosClientException exception) {
            throw unavailable(exception);
        }
    }

    private static BusinessException unavailable(CosClientException exception) {
        LOGGER.warn("COS operation unavailable ({})", exception.getClass().getSimpleName());
        return new BusinessException("CLOUD_STORAGE_UNAVAILABLE", "云存储暂时不可用，请稍后重试", 503);
    }

    private static void requireKey(String key) {
        Data.require(
                key != null && KEY.matcher(key).matches() && !key.contains(".."),
                "INVALID_FILE_PATH",
                "文件路径无效");
    }

    @PreDestroy
    public void close() {
        client.shutdown();
    }
}
