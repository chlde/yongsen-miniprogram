package com.yongsen.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** Private media storage, bounded format inspection, and PDF page rendering. */
@Service
public class MediaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaService.class);
    private static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;
    private static final long PDF_MAX_BYTES = 50L * 1024 * 1024;
    private static final long TICKET_TTL_MS = 15L * 60 * 1000;
    private final Store store;
    private final TransactionTemplate transactions;
    private final Path directory;
    private final String publicBase;
    private final String ffprobe;
    private final String pdftoppm;
    private final String pdfinfo;
    private final long uploadMaxBytes;
    private static final long PROCESS_LEASE_MS = 20L * 60 * 1000;
    private static final long PROCESS_BUDGET_MS = 5L * 60 * 1000;
    private MediaObjectStorage objectStorage;
    private BackgroundWorkDispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public record DownloadFile(Path path, String contentType, long size) {}

    public MediaService(
            Store store,
            TransactionTemplate transactions,
            @Value("${training.data-dir}") String directory,
            @Value("${training.public-base}") String publicBase,
            @Value("${training.ffprobe:ffprobe}") String ffprobe,
            @Value("${training.pdftoppm:pdftoppm}") String pdftoppm,
            @Value("${training.pdfinfo:pdfinfo}") String pdfinfo,
            @Value("${training.max-upload-bytes:524288000}") long uploadMaxBytes)
            throws IOException {
        if (uploadMaxBytes <= 0 || uploadMaxBytes > VIDEO_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Upload limit must be between 1 and 524288000 bytes");
        }
        this.store = store;
        this.transactions = transactions;
        this.directory = Path.of(directory).toAbsolutePath().normalize().resolve("files");
        this.publicBase = publicBase.replaceAll("/+$", "");
        this.ffprobe = ffprobe;
        this.pdftoppm = pdftoppm;
        this.pdfinfo = pdfinfo;
        this.uploadMaxBytes = uploadMaxBytes;
        Files.createDirectories(this.directory);
    }

    @Autowired(required = false)
    public void setObjectStorage(MediaObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Autowired(required = false)
    public void setDispatcher(BackgroundWorkDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public Map<String, Object> uploadLimits() {
        return Data.map(
                "videoMaxBytes",
                Math.min(VIDEO_MAX_BYTES, uploadMaxBytes),
                "pdfMaxBytes",
                Math.min(PDF_MAX_BYTES, uploadMaxBytes));
    }

    public Map<String, Object> create(Map<String, Object> request, String adminId) {
        String title = Data.required(request, "title");
        String type = Data.required(request, "type");
        Data.require(
                title.length() <= 120 && List.of("MP4", "PDF").contains(type),
                "INVALID_MATERIAL",
                "仅支持 MP4 和 PDF，名称不超过120字");
        long size = Data.num(request, "size", 0);
        Data.require(
                size > 0
                        && size
                                <= Math.min(
                                        "MP4".equals(type) ? VIDEO_MAX_BYTES : PDF_MAX_BYTES,
                                        uploadMaxBytes),
                "FILE_TOO_LARGE",
                "文件大小超过限制或文件为空");
        String raw = Data.token();
        String materialId = Data.id();
        String uploadKey = "uploads/" + materialId + "/" + Data.id();
        Instant expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + TICKET_TTL_MS);
        String contentType = "MP4".equals(type) ? "video/mp4" : "application/pdf";
        MediaObjectStorage.Upload direct =
                objectStorage == null
                        ? null
                        : objectStorage.createUpload(uploadKey, contentType, size, expiresAt);
        Map<String, Object> material =
                store.create(
                        Store.Table.MATERIAL,
                        Data.map(
                                "id",
                                materialId,
                                "storageMode",
                                objectStorage == null ? "local" : "cos",
                                "uploadKey",
                                objectStorage == null ? null : uploadKey,
                                "title",
                                title,
                                "type",
                                type,
                                "size",
                                size,
                                "status",
                                "UPLOADING",
                                "compatibilityChecked",
                                false,
                                "createdBy",
                                adminId,
                                "uploadHash",
                                Data.hash(raw),
                                "uploadExpiresMs",
                                System.currentTimeMillis() + TICKET_TTL_MS,
                                "uploaded",
                                false),
                        null,
                        null,
                        "UPLOADING",
                        null);
        store.audit(
                adminId,
                "MATERIAL_UPLOAD_CREATED",
                Data.str(material, "id"),
                Data.map("type", type, "size", size));
        return Data.map(
                "uploadMethod",
                "PUT",
                "uploadHeaders",
                direct == null ? Map.of("Content-Type", contentType) : direct.headers(),
                "expiresAt",
                expiresAt.toString(),
                "id",
                material.get("id"),
                "materialId",
                material.get("id"),
                "status",
                "UPLOADING",
                "uploadUrl",
                direct == null
                        ? publicBase
                                + "/api/v1/media/uploads/"
                                + material.get("id")
                                + "?ticket="
                                + raw
                        : direct.url());
    }

    public void upload(String id, String ticket, InputStream input) throws IOException {
        Map<String, Object> material =
                transactions.execute(
                        status -> {
                            Map<String, Object> row =
                                    store.lock(Store.Table.MATERIAL, id).toBoundary();
                            Data.require(
                                    !"cos".equals(row.get("storageMode"))
                                            && "UPLOADING".equals(row.get("status"))
                                            && !Data.bool(row, "uploaded")
                                            && !Data.bool(row, "uploadInProgress")
                                            && Data.hash(ticket).equals(row.get("uploadHash"))
                                            && Data.num(row, "uploadExpiresMs", 0)
                                                    > System.currentTimeMillis(),
                                    "UPLOAD_TICKET_INVALID",
                                    "上传凭据无效或已使用");
                            row.put("uploadInProgress", true);
                            store.save(Store.Table.MATERIAL, row, "UPLOADING");
                            return row;
                        });
        Path folder = safePath(id);
        Files.createDirectories(folder);
        Path temporary = folder.resolve("upload.part");
        long expected = Data.num(material, "size", 0);
        long written = 0;
        MessageDigest digest = sha256();
        try (DigestInputStream source = new DigestInputStream(input, digest);
                OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) {
                written += read;
                if (written > expected) {
                    throw new BusinessException("UPLOAD_SIZE_MISMATCH", "上传内容超过声明大小", 422);
                }
                output.write(buffer, 0, read);
            }
            Data.require(written == expected, "UPLOAD_SIZE_MISMATCH", "上传文件不完整，请重新上传");
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            reject(id, "上传中断或大小不符合声明，请重新创建上传");
            throw exception;
        }
        String relative =
                id + ("MP4".equals(material.get("type")) ? "/original.mp4" : "/original.pdf");
        Files.move(temporary, safePath(relative), StandardCopyOption.REPLACE_EXISTING);
        String hash = HexFormat.of().formatHex(digest.digest());
        transactions.executeWithoutResult(
                status -> {
                    Map<String, Object> row = store.lock(Store.Table.MATERIAL, id).toBoundary();
                    row.put("uploaded", true);
                    row.put("uploadInProgress", false);
                    row.put("originalKey", relative);
                    row.put("sha256", hash);
                    row.remove("uploadHash");
                    store.save(Store.Table.MATERIAL, row, "UPLOADING");
                });
    }

    public Map<String, Object> completeUpload(String id) {
        Map<String, Object> snapshot = store.get(Store.Table.MATERIAL, id).toBoundary();
        if (!"UPLOADING".equals(snapshot.get("status"))) {
            return publicMaterial(snapshot);
        }
        // At most one bounded HEAD, outside the transaction and its material row lock.
        // Acceptance binds this version; freezing and inspection belong to the persisted job.
        MediaObjectStorage.Metadata source =
                "cos".equals(snapshot.get("storageMode"))
                        ? requireObjectStorage().stat(Data.str(snapshot, "uploadKey"))
                        : null;
        return transactions.execute(
                status -> {
                    Map<String, Object> row = store.lock(Store.Table.MATERIAL, id).toBoundary();
                    if (!"UPLOADING".equals(row.get("status"))) {
                        return publicMaterial(row);
                    }
                    if ("cos".equals(row.get("storageMode"))) {
                        Data.require(
                                source != null
                                        && Data.str(row, "uploadKey")
                                                .equals(Data.str(snapshot, "uploadKey")),
                                "UPLOAD_SOURCE_CHANGED",
                                "上传信息已变化，请重新确认");
                        Data.require(
                                source.size() == Data.num(row, "size", 0),
                                "UPLOAD_SIZE_MISMATCH",
                                "上传文件大小与登记不符");
                        Data.require(
                                source.eTag() != null && !source.eTag().isBlank(),
                                "UPLOAD_INCOMPLETE",
                                "上传文件版本尚不可确认，请稍后重试");
                        row.put("archiveSourceETag", source.eTag());
                        row.put(
                                "archiveKey",
                                "materials/"
                                        + id
                                        + "/source/"
                                        + Data.id()
                                        + ("MP4".equals(row.get("type")) ? ".mp4" : ".pdf"));
                        row.put("uploaded", true);
                        row.remove("uploadHash");
                    }
                    Data.require(Data.bool(row, "uploaded"), "UPLOAD_INCOMPLETE", "文件尚未完整上传");
                    row.put("status", "PROCESSING");
                    store.save(Store.Table.MATERIAL, row, "PROCESSING");
                    if (dispatcher != null) {
                        dispatcher.scheduleAfterCommit("MEDIA", id);
                    }
                    return publicMaterial(row);
                });
    }

    private MediaObjectStorage requireObjectStorage() {
        if (objectStorage == null) {
            throw new IllegalStateException("COS storage is required for this material");
        }
        return objectStorage;
    }

    public Map<String, Object> compatibility(String id, boolean passed, String adminId) {
        return transactions.execute(
                status -> {
                    Map<String, Object> row = store.lock(Store.Table.MATERIAL, id).toBoundary();
                    Data.require(
                            "READY".equals(row.get("status")), "MATERIAL_NOT_READY", "材料尚未处理完成");
                    Data.require(
                            store.all(Store.Table.TASK_MATERIAL).stream()
                                    .map(StoredRecordDO::toBoundary)
                                    .toList()
                                    .stream()
                                    .noneMatch(item -> id.equals(item.get("materialId"))),
                            "MATERIAL_IN_USE",
                            "已被任务引用的材料不可修改");
                    row.put("compatibilityChecked", passed);
                    row.put("compatibilityCheckedAt", Data.now());
                    store.save(Store.Table.MATERIAL, row, "READY");
                    store.audit(
                            adminId, "MATERIAL_PREVIEW_CONFIRMED", id, Data.map("passed", passed));
                    return publicMaterial(row);
                });
    }

    public Map<String, Object> get(String id) {
        return publicMaterial(store.get(Store.Table.MATERIAL, id).toBoundary());
    }

    public List<Map<String, Object>> list() {
        return store.all(Store.Table.MATERIAL).stream()
                .map(StoredRecordDO::toBoundary)
                .map(this::publicMaterial)
                .toList();
    }

    private Map<String, Object> publicMaterial(Map<String, Object> source) {
        Map<String, Object> result = Store.publicView(source);
        result.remove("originalKey");
        result.remove("uploadExpiresMs");
        result.remove("uploadKey");
        result.remove("archiveKey");
        result.remove("archiveSourceETag");
        result.remove("storageMode");
        result.remove("processClaim");
        result.remove("processLeaseUntilMs");
        result.remove("processingFence");
        result.remove("uploadInProgress");
        List<Map<String, Object>> pages = new ArrayList<>();
        for (Object value : Data.list(source.get("pages"))) {
            Map<String, Object> page = Data.obj(value);
            pages.add(
                    Data.map(
                            "pageNo",
                            page.get("pageNo"),
                            "width",
                            page.get("width"),
                            "height",
                            page.get("height"),
                            "sha256",
                            page.get("sha256")));
        }
        result.put("pages", pages);
        return result;
    }

    /** Caller must first check administrator or assignment/material authorization. */
    public Map<String, Object> ticket(String materialId, Integer pageNo) {
        Map<String, Object> material = store.get(Store.Table.MATERIAL, materialId).toBoundary();
        Data.require("READY".equals(material.get("status")), "MATERIAL_NOT_READY", "材料尚不可访问");
        String relative = Data.str(material, "originalKey");
        String contentType = "video/mp4";
        if ("PDF".equals(material.get("type"))) {
            int requested = pageNo == null ? 1 : pageNo;
            List<Object> pages = Data.list(material.get("pages"));
            Data.require(requested > 0 && requested <= pages.size(), "PAGE_INVALID", "页码无效");
            relative = Data.str(Data.obj(pages.get(requested - 1)), "key");
            contentType = "image/png";
        }
        long expires = System.currentTimeMillis() + TICKET_TTL_MS;
        if ("cos".equals(material.get("storageMode"))) {
            return Data.map(
                    "url",
                    requireObjectStorage()
                            .signedGet(relative, contentType, Instant.ofEpochMilli(expires)),
                    "expiresAt",
                    Instant.ofEpochMilli(expires).toString());
        }
        String raw = Data.token();
        expires = System.currentTimeMillis() + TICKET_TTL_MS;
        store.create(
                Store.Table.MEDIA_TICKET,
                Data.map(
                        "materialId",
                        materialId,
                        "fileKey",
                        relative,
                        "contentType",
                        contentType,
                        "expiresMs",
                        expires),
                materialId,
                null,
                "ACTIVE",
                Data.hash(raw));
        return Data.map(
                "url",
                publicBase + "/api/v1/media/" + raw + "/file",
                "expiresAt",
                Instant.ofEpochMilli(expires).toString());
    }

    public DownloadFile download(String raw) throws IOException {
        Map<String, Object> ticket =
                store.byKey(Store.Table.MEDIA_TICKET, Data.hash(raw)).toBoundary();
        Data.require(
                Data.num(ticket, "expiresMs", 0) > System.currentTimeMillis(),
                "MEDIA_TICKET_EXPIRED",
                "文件访问已过期，请刷新");
        Path file = safePath(Data.str(ticket, "fileKey"));
        Data.require(Files.isRegularFile(file), "MEDIA_NOT_FOUND", "文件不可用");
        return new DownloadFile(file, Data.str(ticket, "contentType"), Files.size(file));
    }

    @Scheduled(fixedDelay = 1000)
    public void processPending() {
        if (dispatcher != null && dispatcher.enabled()) {
            return;
        }
        List<StoredRecordDO> pending = store.status(Store.Table.MATERIAL, "PROCESSING");
        for (StoredRecordDO row : pending) {
            if (Data.num(row.toBoundary(), "processLeaseUntilMs", 0)
                    <= System.currentTimeMillis()) {
                try {
                    processOne(row.id());
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                            "Media worker deferred for {}: {}",
                            row.id(),
                            exception.getClass().getSimpleName());
                }
                return;
            }
        }
    }

    /** Processes one persisted material with a database lease and a fenced publication. */
    public void processOne(String id) {
        Map<String, Object> material =
                transactions.execute(
                        status -> {
                            Map<String, Object> row =
                                    store.lock(Store.Table.MATERIAL, id).toBoundary();
                            if (!"PROCESSING".equals(row.get("status"))) {
                                return null;
                            }
                            Data.require(
                                    Data.num(row, "processLeaseUntilMs", 0)
                                            <= System.currentTimeMillis(),
                                    "MEDIA_BUSY",
                                    "材料仍在处理中，请稍后重试");
                            row.put("processClaim", Data.id());
                            row.put("processingFence", Data.num(row, "processingFence", 0) + 1);
                            row.put(
                                    "processLeaseUntilMs",
                                    System.currentTimeMillis() + PROCESS_LEASE_MS);
                            store.save(Store.Table.MATERIAL, row, "PROCESSING");
                            return row;
                        });
        if (material == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_BUDGET_MS);
        Path work = null;
        try {
            work = Files.createTempDirectory(directory, "media-work-");
            boolean remote = "cos".equals(material.get("storageMode"));
            Path file =
                    remote ? work.resolve("source") : safePath(Data.str(material, "originalKey"));
            if (remote) {
                freezeSource(material, deadline);
                requireObjectStorage()
                        .download(
                                Data.str(material, "originalKey"),
                                file,
                                Data.num(material, "size", 0));
            }
            checkDeadline(deadline);
            Data.require(
                    Files.size(file) == Data.num(material, "size", 0),
                    "UPLOAD_SIZE_MISMATCH",
                    "材料文件大小不符");
            String sha256 = hashFile(file);
            checkDeadline(deadline);
            byte[] header;
            try (InputStream input = Files.newInputStream(file)) {
                header = input.readNBytes(12);
            }
            Map<String, Object> metadata;
            if ("MP4".equals(material.get("type"))) {
                Data.require(
                        header.length >= 8
                                && new String(
                                                header,
                                                4,
                                                4,
                                                java.nio.charset.StandardCharsets.US_ASCII)
                                        .equals("ftyp"),
                        "INVALID_MP4",
                        "文件不是有效 MP4");
                metadata = inspectVideo(file, deadline);
            } else {
                Data.require(
                        header.length >= 5
                                && new String(
                                                header,
                                                0,
                                                5,
                                                java.nio.charset.StandardCharsets.US_ASCII)
                                        .equals("%PDF-"),
                        "INVALID_PDF",
                        "文件不是有效 PDF");
                metadata = inspectPdf(file, material, work, deadline);
            }
            metadata.put("sha256", sha256);
            checkDeadline(deadline);
            transactions.executeWithoutResult(
                    status -> {
                        Map<String, Object> row = store.lock(Store.Table.MATERIAL, id).toBoundary();
                        requireClaim(row, material);
                        row.putAll(metadata);
                        row.remove("reason");
                        row.put("status", "READY");
                        row.remove("processClaim");
                        row.remove("processLeaseUntilMs");
                        store.save(Store.Table.MATERIAL, row, "READY");
                    });
        } catch (BusinessException exception) {
            if ("MEDIA_CLAIM_EXPIRED".equals(exception.code())) {
                throw exception;
            }
            if (exception.status() >= 500) {
                finishFailedClaim(material, "云存储暂时不可用，请重试", false);
                throw exception;
            }
            finishFailedClaim(material, exception.getMessage(), true);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            finishFailedClaim(material, "材料暂时无法处理，请重试", false);
            throw new IllegalStateException("Material processing did not finish", exception);
        } catch (RuntimeException exception) {
            finishFailedClaim(material, "材料暂时无法处理，请重试", false);
            throw exception;
        } finally {
            cleanupWork(work);
        }
    }

    private void freezeSource(Map<String, Object> material, long deadline) {
        // Older PROCESSING records already contain a frozen originalKey. Never rebind them
        // to staging, even if the original upload has since been replaced or removed.
        if (!Data.str(material, "originalKey").isBlank()) {
            return;
        }
        String target = Data.str(material, "archiveKey");
        String sourceETag = Data.str(material, "archiveSourceETag");
        Data.require(
                !target.isBlank() && !sourceETag.isBlank(), "UPLOAD_INCOMPLETE", "上传文件版本未确认，请重新上传");
        MediaObjectStorage storage = requireObjectStorage();
        checkDeadline(deadline);
        var archived = storage.find(target);
        if (archived.isEmpty()) {
            checkDeadline(deadline);
            try {
                storage.copyIfMatch(Data.str(material, "uploadKey"), target, sourceETag);
            } catch (BusinessException exception) {
                // A timed-out COPY can have committed, including under an earlier claim.
                // Only the server-authored marker on this unique key can recover that result.
                checkDeadline(deadline);
                archived = storage.find(target);
                if (archived.isEmpty()) {
                    throw exception;
                }
            }
            if (archived.isEmpty()) {
                checkDeadline(deadline);
                archived = storage.find(target);
            }
        }
        MediaObjectStorage.Metadata frozen =
                archived.orElseThrow(
                        () ->
                                new BusinessException(
                                        "CLOUD_STORAGE_UNAVAILABLE", "归档结果暂时不可确认，请重试", 503));
        if (frozen.size() != Data.num(material, "size", 0)
                || !sourceETag.equals(frozen.archiveSourceETag())) {
            throw new BusinessException("MEDIA_ARCHIVE_MISMATCH", "归档文件信息不一致，请联系管理员", 503);
        }
        checkDeadline(deadline);
        transactions.executeWithoutResult(
                status -> {
                    Map<String, Object> row =
                            store.lock(Store.Table.MATERIAL, Data.str(material, "id")).toBoundary();
                    requireClaim(row, material);
                    row.put("originalKey", target);
                    store.save(Store.Table.MATERIAL, row, "PROCESSING");
                });
        material.put("originalKey", target);
    }

    private void requireClaim(Map<String, Object> row, Map<String, Object> claim) {
        Data.require(
                "PROCESSING".equals(row.get("status"))
                        && Data.str(row, "processClaim").equals(Data.str(claim, "processClaim"))
                        && Data.num(row, "processingFence", 0)
                                == Data.num(claim, "processingFence", -1)
                        && Data.num(row, "processLeaseUntilMs", 0) > System.currentTimeMillis(),
                "MEDIA_CLAIM_EXPIRED",
                "材料处理任务已过期，将由新任务继续");
    }

    private void finishFailedClaim(Map<String, Object> claim, String message, boolean rejected) {
        transactions.executeWithoutResult(
                status -> {
                    Map<String, Object> row =
                            store.lock(Store.Table.MATERIAL, Data.str(claim, "id")).toBoundary();
                    if (!"PROCESSING".equals(row.get("status"))
                            || !Data.str(row, "processClaim")
                                    .equals(Data.str(claim, "processClaim"))) {
                        return;
                    }
                    row.put("status", rejected ? "REJECTED" : "PROCESSING");
                    row.put("reason", message);
                    row.remove("processClaim");
                    row.remove("processLeaseUntilMs");
                    store.save(Store.Table.MATERIAL, row, Data.str(row, "status"));
                });
    }

    private static void checkDeadline(long deadline) {
        Data.require(System.nanoTime() < deadline, "MEDIA_PROCESS_TIMEOUT", "材料处理超时，请缩小或重新导出文件");
    }

    private static void cleanupWork(Path work) {
        if (work == null) {
            return;
        }
        try (var paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            LOGGER.warn(
                    "Could not clear temporary media work: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private Map<String, Object> inspectVideo(Path file, long deadline)
            throws IOException, InterruptedException {
        JsonNode info =
                mapper.readTree(
                        command(
                                List.of(
                                        ffprobe,
                                        "-v",
                                        "error",
                                        "-show_entries",
                                        "format=duration:stream=codec_type,codec_name,pix_fmt",
                                        "-of",
                                        "json",
                                        file.toString()),
                                30,
                                deadline));
        double seconds = info.path("format").path("duration").asDouble();
        Data.require(
                Double.isFinite(seconds) && seconds > 0 && seconds <= 3600,
                "VIDEO_DURATION_INVALID",
                "视频时长须在60分钟以内");
        boolean videoFound = false;
        for (JsonNode stream : info.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                Data.require(
                        "h264".equals(stream.path("codec_name").asText())
                                && "yuv420p".equals(stream.path("pix_fmt").asText()),
                        "VIDEO_CODEC_UNSUPPORTED",
                        "请导出 H.264 / yuv420p 格式的 MP4");
                videoFound = true;
            } else if ("audio".equals(stream.path("codec_type").asText())) {
                Data.require(
                        "aac".equals(stream.path("codec_name").asText()),
                        "AUDIO_CODEC_UNSUPPORTED",
                        "请导出 AAC 音轨");
            }
        }
        Data.require(videoFound, "VIDEO_MISSING", "文件没有可用视频轨道");
        return Data.map("durationMs", Math.round(seconds * 1000), "pageCount", 0);
    }

    private Map<String, Object> inspectPdf(
            Path file, Map<String, Object> material, Path work, long deadline)
            throws IOException, InterruptedException {
        String information = command(List.of(pdfinfo, file.toString()), 30, deadline);
        Matcher pageMatch = Pattern.compile("(?m)^Pages:\\s+(\\d+)").matcher(information);
        Data.require(pageMatch.find(), "PDF_INVALID", "无法读取 PDF 页数");
        int count = Integer.parseInt(pageMatch.group(1));
        Data.require(
                count > 0
                        && count <= 100
                        && !Pattern.compile("(?m)^Encrypted:\\s+yes").matcher(information).find(),
                "PDF_UNSUPPORTED",
                "PDF 必须无密码且不超过100页");
        Path pagesDirectory = work.resolve("pages");
        Files.createDirectories(pagesDirectory);
        command(
                List.of(
                        pdftoppm,
                        "-png",
                        "-scale-to",
                        "1400",
                        file.toString(),
                        pagesDirectory.resolve("page").toString()),
                120,
                deadline);
        List<Path> images;
        try (var paths = Files.list(pagesDirectory)) {
            images =
                    paths.filter(path -> path.getFileName().toString().matches("page-\\d+\\.png"))
                            .sorted(
                                    Comparator.comparingInt(
                                            path ->
                                                    Integer.parseInt(
                                                            path.getFileName()
                                                                    .toString()
                                                                    .replaceAll("\\D", ""))))
                            .toList();
        }
        Data.require(images.size() == count, "PDF_PAGE_MISSING", "PDF 页面渲染不完整");
        List<Map<String, Object>> pages = new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            checkDeadline(deadline);
            Path image = images.get(index);
            BufferedImage decoded = ImageIO.read(image.toFile());
            Data.require(
                    decoded != null && (long) decoded.getWidth() * decoded.getHeight() <= 3_000_000,
                    "PDF_RENDER_INVALID",
                    "PDF 页面无法正常显示");
            String pageKey =
                    "materials/"
                            + Data.str(material, "id")
                            + "/render/"
                            + Data.str(material, "processClaim")
                            + "/page-"
                            + (index + 1)
                            + ".png";
            if ("cos".equals(material.get("storageMode"))) {
                requireObjectStorage().put(pageKey, image, "image/png");
            } else {
                Path target = safePath(pageKey);
                Files.createDirectories(target.getParent());
                Files.copy(image, target);
            }
            pages.add(
                    Data.map(
                            "pageNo",
                            index + 1,
                            "width",
                            decoded.getWidth(),
                            "height",
                            decoded.getHeight(),
                            "key",
                            pageKey,
                            "sha256",
                            hashFile(image)));
        }
        return Data.map(
                "pageCount", count, "pages", pages, "renderVersion", "poppler-scale1400-v1");
    }

    private String command(List<String> arguments, int seconds, long deadline)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile(directory, "process-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(arguments)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            checkDeadline(deadline);
            long remainingMs =
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (!process.waitFor(Math.min(seconds * 1000L, remainingMs), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new BusinessException("MEDIA_PROCESS_TIMEOUT", "材料处理超时，请缩小或重新导出文件", 422);
            }
            Data.require(process.exitValue() == 0, "MEDIA_PROCESS_FAILED", "文件无法解析，请重新导出并上传");
            try (InputStream input = Files.newInputStream(output)) {
                return new String(
                        input.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(output);
        }
    }

    private Path safePath(String relative) {
        Path file = directory.resolve(relative).normalize();
        Data.require(
                file.startsWith(directory) && !file.equals(directory),
                "INVALID_FILE_PATH",
                "文件路径无效");
        return file;
    }

    private void reject(String id, String reason) {
        transactions.executeWithoutResult(
                status -> {
                    Map<String, Object> row = store.lock(Store.Table.MATERIAL, id).toBoundary();
                    row.put("status", "REJECTED");
                    row.put("reason", reason);
                    store.save(Store.Table.MATERIAL, row, "REJECTED");
                });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
