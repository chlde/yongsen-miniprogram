package com.yongsen.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

class CosMediaObjectStorageTest {
    @Test
    void officialSdkSignsUploadLengthTypeHostAndTemporaryTokenWithoutNetwork() {
        CloudCredentials source = mock(CloudCredentials.class);
        when(source.current())
                .thenReturn(new CloudCredentials.Value("test-id", "test-key", "test-session"));
        COSClient client = CosMediaObjectStorage.createClient("ap-guangzhou", source);
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        try {
            assertThat(client.getClientConfig().getRequestTimeout()).isEqualTo(30_000);
            assertThat(client.getClientConfig().getRequestTimeOutEnable()).isTrue();
            assertThat(client.getClientConfig().getMaxErrorRetry()).isZero();
            assertThat(client.getClientConfig().getMaxErrorRetryForCopyRequest()).isZero();
            assertThat(client.getClientConfig().getConnectionRequestTimeout()).isEqualTo(10_000);
            assertThat(client.getClientConfig().isChangeEndpointRetry()).isFalse();
            assertThat(client.getClientConfig().isRetryAfterPreflight()).isFalse();
            MediaObjectStorage.Upload upload =
                    storage.createUpload(
                            "uploads/material/staging",
                            "video/mp4",
                            100,
                            Instant.now().plusSeconds(900));
            URI uri = URI.create(upload.url());
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost())
                    .isEqualTo("test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com");
            assertThat(upload.method()).isEqualTo("PUT");
            assertThat(upload.headers())
                    .isEqualTo(Map.of("Content-Type", "video/mp4", "x-cos-acl", "default"));
            String decoded = URLDecoder.decode(upload.url(), StandardCharsets.UTF_8);
            assertThat(decoded)
                    .contains("q-header-list=content-length;content-type;host;x-cos-acl");
            assertThat(decoded).contains("x-cos-security-token=test-session");
            assertThat(decoded).doesNotContain("test-key");
            String get =
                    URLDecoder.decode(
                            storage.signedGet(
                                    "materials/material/source/file.mp4",
                                    "video/mp4",
                                    Instant.now().plusSeconds(900)),
                            StandardCharsets.UTF_8);
            assertThat(get).contains("q-header-list=host").doesNotContain("range;");
        } finally {
            storage.close();
        }
    }

    @Test
    void freezeUsesSdkConditionalCopyAndNeverTargetsUploadPrefix() {
        COSClient client = mock(COSClient.class);
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        storage.copyIfMatch("uploads/id/source", "materials/id/source/frozen.pdf", "etag-1");
        ArgumentCaptor<CopyObjectRequest> request =
                ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().getMatchingETagConstraints()).containsExactly("etag-1");
        assertThat(request.getValue().getDestinationKey())
                .isEqualTo("materials/id/source/frozen.pdf");
        assertThat(request.getValue().getMetadataDirective()).isEqualTo("REPLACE");
        assertThat(request.getValue().getNewObjectMetadata().getUserMetadata())
                .containsExactlyEntriesOf(Map.of("training-source-etag", "etag-1"));
        assertThat(request.getValue().getNewObjectMetadata().getContentType())
                .isEqualTo("application/pdf");
        assertThrows(
                BusinessException.class,
                () -> storage.copyIfMatch("uploads/id/source", "uploads/id/target", "etag-1"));
        assertThrows(
                BusinessException.class,
                () -> storage.signedGet("uploads/id/source", "video/mp4", Instant.now()));
        assertThrows(BusinessException.class, () -> storage.stat("materials/../../secret"));
    }

    @Test
    void downloadSignatureExpiresAndDoesNotBindRangeHeader() throws Exception {
        COSClient client = mock(COSClient.class);
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(
                        URI.create(
                                        "https://test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com/object?signature=test")
                                .toURL());
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        Instant expiry = Instant.now().plusSeconds(900);
        storage.signedGet("materials/id/source/file.mp4", "video/mp4", expiry);
        ArgumentCaptor<GeneratePresignedUrlRequest> request =
                ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(client).generatePresignedUrl(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethodName.GET);
        assertThat(request.getValue().getExpiration().toInstant())
                .isEqualTo(Instant.ofEpochMilli(expiry.toEpochMilli()));
        assertThat(request.getValue().getResponseHeaders().getCacheControl())
                .isEqualTo("private, no-store");
        assertThat(request.getValue().getResponseHeaders().getContentType()).isEqualTo("video/mp4");
        assertThat(request.getValue().getRequestParameters()).doesNotContainKey("range");
    }

    @Test
    void storageErrorsNeverExposeProviderRequestDetails() {
        COSClient client = mock(COSClient.class);
        when(client.getObjectMetadata("test-bucket-1250000000", "materials/id/source/file.mp4"))
                .thenThrow(new CosClientException("private authorization request details"));
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> storage.stat("materials/id/source/file.mp4"));
        assertThat(error.code()).isEqualTo("CLOUD_STORAGE_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(503);
        assertThat(error.getMessage()).doesNotContain("authorization");
        assertThat(error.getCause()).isNull();
    }

    @Test
    void archiveHeadReadsServerMarkerAndDoesNotConfuseBucketOrPermissionFailureWithAbsence() {
        COSClient client = mock(COSClient.class);
        String key = "materials/id/source/frozen.pdf";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(17);
        metadata.addUserMetadata("training-source-etag", "confirmed-etag");
        when(client.getObjectMetadata("test-bucket-1250000000", key)).thenReturn(metadata);
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        assertThat(storage.find(key).orElseThrow().archiveSourceETag()).isEqualTo("confirmed-etag");
        for (String code : new String[] {"NoSuchBucket", "AccessDenied"}) {
            doThrow(providerFailure("NoSuchBucket".equals(code) ? 404 : 403, code))
                    .when(client)
                    .getObjectMetadata("test-bucket-1250000000", key);
            assertThat(assertThrows(BusinessException.class, () -> storage.find(key)).status())
                    .isEqualTo(503);
        }
        for (String code : new String[] {"NoSuchKey", "404 Not Found"}) {
            doThrow(providerFailure(404, code))
                    .when(client)
                    .getObjectMetadata("test-bucket-1250000000", key);
            assertThat(storage.find(key)).isEmpty();
        }
    }

    @Test
    void onlyDefinitiveCopySourceChangeIsRejectedAndTransientFailuresRemainRetryable() {
        COSClient client = mock(COSClient.class);
        CosMediaObjectStorage storage = new CosMediaObjectStorage(client, "test-bucket-1250000000");
        for (String code :
                new String[] {
                    "PreconditionFailed",
                    "NoSuchKey",
                    "NoSuchCopySource",
                    "NoSuchBucket",
                    "AccessDenied",
                    "ServiceUnavailable"
                }) {
            int status =
                    "PreconditionFailed".equals(code)
                            ? 412
                            : "AccessDenied".equals(code)
                                    ? 403
                                    : "ServiceUnavailable".equals(code) ? 503 : 404;
            doThrow(providerFailure(status, code))
                    .when(client)
                    .copyObject(any(CopyObjectRequest.class));
            BusinessException error =
                    assertThrows(
                            BusinessException.class,
                            () ->
                                    storage.copyIfMatch(
                                            "uploads/id/source",
                                            "materials/id/source/frozen.pdf",
                                            "etag"));
            boolean sourceChanged =
                    java.util.Set.of("PreconditionFailed", "NoSuchKey", "NoSuchCopySource")
                            .contains(code);
            assertThat(error.status()).isEqualTo(sourceChanged ? 422 : 503);
            assertThat(error.code())
                    .isEqualTo(
                            sourceChanged ? "UPLOAD_SOURCE_CHANGED" : "CLOUD_STORAGE_UNAVAILABLE");
            assertThat(error.getCause()).isNull();
        }
    }

    private static CosServiceException providerFailure(int status, String code) {
        CosServiceException error = new CosServiceException("private provider details");
        error.setStatusCode(status);
        error.setErrorCode(code);
        return error;
    }

    @Test
    void invalidDeploymentRegionCannotChooseAnArbitraryEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CosMediaObjectStorage.createClient(
                                "https://attacker.example", mock(CloudCredentials.class)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CosMediaObjectStorage(mock(COSClient.class), "bucket/path"));
    }
}
