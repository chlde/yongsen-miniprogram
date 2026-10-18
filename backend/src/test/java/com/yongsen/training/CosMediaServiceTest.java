package com.yongsen.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class CosMediaServiceTest {
    @TempDir Path directory;
    BackendFixture fixture;
    FakeMediaObjectStorage storage;
    MediaService media;

    @BeforeEach
    void setup() throws IOException {
        fixture = new BackendFixture();
        storage = new FakeMediaObjectStorage();
        media = service();
    }

    @Test
    void directUploadUsesAuthoritativeHeadersAndHidesInternalState() {
        Map<String, Object> created =
                media.create(Data.map("title", "PDF", "type", "PDF", "size", 5), "admin");
        assertThat(created.get("uploadMethod")).isEqualTo("PUT");
        assertThat(created.get("uploadHeaders"))
                .isEqualTo(Map.of("Content-Type", "application/pdf"));
        assertThat(created.get("uploadUrl").toString())
                .startsWith("https://private.example/uploads/");
        Map<String, Object> view = media.get(Data.str(created, "id"));
        assertThat(view)
                .doesNotContainKeys("uploadKey", "uploadHash", "storageMode", "processClaim");
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                media.upload(
                                        Data.str(created, "id"),
                                        "forged",
                                        new ByteArrayInputStream(new byte[5])));
        assertThat(error.code()).isEqualTo("UPLOAD_TICKET_INVALID");
    }

    @Test
    void incompleteOrOversizeUploadCannotBecomeProcessing() {
        Map<String, Object> created =
                media.create(Data.map("title", "PDF", "type", "PDF", "size", 5), "admin");
        String id = Data.str(created, "id");
        storage.objects.put(storage.lastUploadKey, new byte[6]);
        assertThat(assertThrows(BusinessException.class, () -> media.completeUpload(id)).code())
                .isEqualTo("UPLOAD_SIZE_MISMATCH");
        assertThat(media.get(id).get("status")).isEqualTo("UPLOADING");
        assertThat(storage.copies).hasValue(0);
    }

    @Test
    void conditionalCopyRejectsAConcurrentOverwrite() {
        String id = upload("PDF", new byte[5]);
        storage.beforeCopy =
                () -> storage.objects.put(storage.lastUploadKey, new byte[] {1, 1, 1, 1, 1});
        media.completeUpload(id);
        media.processOne(id);
        assertThat(media.get(id).get("status")).isEqualTo("REJECTED");
        assertThat(media.get(id).get("reason").toString()).contains("修改");
        assertThat(media.get(id).get("sha256")).isNull();
        assertThrows(BusinessException.class, () -> media.ticket(id, null));
        assertThat(storage.copies).hasValue(0);
    }

    @Test
    void frozenSourceCannotBeChangedWithTheOldPutUrlAndCompletionIsIdempotent() throws IOException {
        byte[] bytes = resource("training.mp4");
        String id = upload("MP4", bytes);
        String oldKey = storage.lastUploadKey;
        BackgroundWorkDispatcher dispatcher = mock(BackgroundWorkDispatcher.class);
        media.setDispatcher(dispatcher);
        storage.beforeStat =
                key ->
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isFalse();
        assertThat(media.completeUpload(id).get("status")).isEqualTo("PROCESSING");
        verify(dispatcher).scheduleAfterCommit("MEDIA", id);
        assertThat(storage.heads).hasValue(1);
        assertThat(storage.copies).hasValue(0);
        Map<String, Object> accepted = fixture.store.get(Store.Table.MATERIAL, id).toBoundary();
        assertThat(accepted)
                .containsKeys("archiveKey", "archiveSourceETag")
                .doesNotContainKey("originalKey");
        assertThat(Store.publicView(accepted))
                .doesNotContainKeys("archiveKey", "archiveSourceETag");
        media.completeUpload(id);
        assertThat(storage.heads).hasValue(1);
        media.processOne(id);
        String frozen =
                Data.str(fixture.store.get(Store.Table.MATERIAL, id).toBoundary(), "originalKey");
        storage.objects.put(oldKey, new byte[] {9, 9, 9});
        media.completeUpload(id);
        assertThat(storage.copies).hasValue(1);
        assertThat(storage.objects.get(frozen)).isEqualTo(bytes);
    }

    @Test
    void copyResponseLossCanRecoverAfterStagingWasOverwritten() throws IOException {
        byte[] bytes = resource("training.mp4");
        String id = upload("MP4", bytes);
        media.completeUpload(id);
        storage.afterCopy =
                () -> {
                    storage.beforeStat =
                            key -> {
                                throw new BusinessException(
                                        "CLOUD_STORAGE_UNAVAILABLE", "HEAD timed out", 503);
                            };
                    throw new BusinessException(
                            "CLOUD_STORAGE_UNAVAILABLE", "COPY response lost", 503);
                };
        assertThrows(BusinessException.class, () -> media.processOne(id));
        assertThat(media.get(id).get("status")).isEqualTo("PROCESSING");
        assertThat(fixture.store.get(Store.Table.MATERIAL, id).toBoundary())
                .doesNotContainKeys("originalKey", "processClaim");
        storage.objects.put(storage.lastUploadKey, new byte[bytes.length]);
        storage.beforeStat = key -> {};
        storage.afterCopy = () -> {};
        service().processOne(id);
        assertThat(media.get(id).get("status")).isEqualTo("READY");
        assertThat(storage.copies).hasValue(1);
        String frozen =
                Data.str(fixture.store.get(Store.Table.MATERIAL, id).toBoundary(), "originalKey");
        assertThat(storage.objects.get(frozen)).isEqualTo(bytes);
        assertScratchEmpty();
    }

    @Test
    void failedJobPersistenceRollsBackConfirmationWithoutCopyingAnObject() throws IOException {
        String id = upload("MP4", resource("training.mp4"));
        BackgroundWorkDispatcher dispatcher = mock(BackgroundWorkDispatcher.class);
        doThrow(new BusinessException("BACKGROUND_WORK_UNAVAILABLE", "Job persistence failed", 503))
                .when(dispatcher).scheduleAfterCommit("MEDIA", id);
        media.setDispatcher(dispatcher);
        assertThrows(BusinessException.class, () -> media.completeUpload(id));
        Map<String, Object> row = fixture.store.get(Store.Table.MATERIAL, id).toBoundary();
        assertThat(row).containsEntry("status", "UPLOADING").containsEntry("uploaded", false)
                .doesNotContainKeys("archiveKey", "archiveSourceETag", "originalKey");
        assertThat(storage.copies).hasValue(0);
        media.setDispatcher(null);
        assertThat(media.completeUpload(id).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    void existingArchiveRequiresServerMarkerNotOnlyMatchingLength() throws IOException {
        byte[] bytes = resource("training.mp4");
        String id = upload("MP4", bytes);
        media.completeUpload(id);
        String archiveKey =
                Data.str(fixture.store.get(Store.Table.MATERIAL, id).toBoundary(), "archiveKey");
        storage.objects.put(archiveKey, bytes);
        assertThat(assertThrows(BusinessException.class, () -> media.processOne(id)).code())
                .isEqualTo("MEDIA_ARCHIVE_MISMATCH");
        assertThat(media.get(id).get("status")).isEqualTo("PROCESSING");
        assertThat(media.get(id).get("sha256")).isNull();
        assertThat(storage.copies).hasValue(0);
        assertThrows(BusinessException.class, () -> media.ticket(id, null));
    }

    @Test
    void olderProcessingRecordUsesItsExistingFrozenSource() throws IOException {
        byte[] bytes = resource("training.mp4");
        String id = upload("MP4", bytes);
        String frozen = "materials/" + id + "/source/legacy.mp4";
        storage.objects.put(frozen, bytes);
        fixture.transactions.executeWithoutResult(
                status -> {
                    Map<String, Object> row =
                            fixture.store.lock(Store.Table.MATERIAL, id).toBoundary();
                    row.put("originalKey", frozen);
                    row.put("uploaded", true);
                    row.put("status", "PROCESSING");
                    fixture.store.save(Store.Table.MATERIAL, row, "PROCESSING");
                });
        storage.objects.remove(storage.lastUploadKey);
        media.processOne(id);
        assertThat(media.get(id).get("status")).isEqualTo("READY");
        assertThat(storage.copies).hasValue(0);
        assertThat(storage.heads).hasValue(0);
    }

    @Test
    void staleWorkerCannotPublishArchiveCheckpoint() throws IOException {
        String id = upload("MP4", resource("training.mp4"));
        media.completeUpload(id);
        storage.afterCopy =
                () ->
                        fixture.transactions.executeWithoutResult(
                                status -> {
                                    Map<String, Object> row =
                                            fixture.store
                                                    .lock(Store.Table.MATERIAL, id)
                                                    .toBoundary();
                                    row.put("processClaim", "new-worker");
                                    row.put(
                                            "processingFence",
                                            Data.num(row, "processingFence", 0) + 1);
                                    fixture.store.save(Store.Table.MATERIAL, row, "PROCESSING");
                                });
        assertThat(assertThrows(BusinessException.class, () -> media.processOne(id)).code())
                .isEqualTo("MEDIA_CLAIM_EXPIRED");
        assertThat(fixture.store.get(Store.Table.MATERIAL, id).toBoundary())
                .containsEntry("processClaim", "new-worker")
                .doesNotContainKeys("originalKey", "sha256");
    }

    @Test
    void workerInspectsRealVideoHashesFrozenBytesAndCleansScratchSpace() throws Exception {
        byte[] bytes = resource("training.mp4");
        String id = upload("MP4", bytes);
        media.completeUpload(id);
        assertThat(media.get(id).get("sha256")).isNull();
        media.processOne(id);
        Map<String, Object> result = media.get(id);
        assertThat(result.get("status")).isEqualTo("READY");
        assertThat(result.get("sha256"))
                .isEqualTo(
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(Data.num(result, "durationMs", 0)).isEqualTo(1000);
        assertThat(result)
                .doesNotContainKeys("processClaim", "processLeaseUntilMs", "processingFence");
        Map<String, Object> ticket = media.ticket(id, null);
        assertThat(ticket.get("url").toString()).startsWith("https://private.example/materials/");
        assertThat(storage.lastDownloadExpiry)
                .isBetween(Instant.now().plusSeconds(890), Instant.now().plusSeconds(901));
        assertScratchEmpty();
    }

    @Test
    void workerRendersRealPdfAndPublishesOnlyCompletePageSet() throws IOException {
        String id = upload("PDF", resource("training.pdf"));
        media.completeUpload(id);
        media.processOne(id);
        Map<String, Object> result = media.get(id);
        assertThat(result.get("status")).isEqualTo("READY");
        assertThat(Data.num(result, "pageCount", 0)).isEqualTo(2);
        assertThat(Data.list(result.get("pages"))).hasSize(2);
        Map<String, Object> internal = fixture.store.get(Store.Table.MATERIAL, id).toBoundary();
        for (Object page : Data.list(internal.get("pages"))) {
            String key = Data.str(Data.obj(page), "key");
            assertThat(storage.objects.get(key))
                    .startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
        }
        assertThat(assertThrows(BusinessException.class, () -> media.ticket(id, 3)).code())
                .isEqualTo("PAGE_INVALID");
        media.ticket(id, 2);
        assertThat(storage.lastDownloadKey).endsWith("/page-2.png");
        assertScratchEmpty();
    }

    @Test
    void failedPageUploadNeverPublishesReadyAndCanRetryWithANewClaim() throws IOException {
        String id = upload("PDF", resource("training.pdf"));
        media.completeUpload(id);
        storage.putFailure =
                new BusinessException("CLOUD_STORAGE_UNAVAILABLE", "temporarily unavailable", 503);
        assertThrows(BusinessException.class, () -> media.processOne(id));
        assertThat(media.get(id).get("status")).isEqualTo("PROCESSING");
        assertThat(Data.list(media.get(id).get("pages"))).isEmpty();
        assertThrows(BusinessException.class, () -> media.ticket(id, 1));
        storage.putFailure = null;
        media.processOne(id);
        assertThat(media.get(id).get("status")).isEqualTo("READY");
        assertScratchEmpty();
    }

    @Test
    void expiredWorkerCannotPublishAfterANewerClaim() throws IOException {
        String id = upload("MP4", resource("training.mp4"));
        media.completeUpload(id);
        storage.beforeDownload =
                () ->
                        fixture.transactions.executeWithoutResult(
                                status -> {
                                    Map<String, Object> row =
                                            fixture.store
                                                    .lock(Store.Table.MATERIAL, id)
                                                    .toBoundary();
                                    row.put("processClaim", "newer-worker");
                                    row.put(
                                            "processingFence",
                                            Data.num(row, "processingFence", 0) + 1);
                                    fixture.store.save(Store.Table.MATERIAL, row, "PROCESSING");
                                });
        assertThat(assertThrows(BusinessException.class, () -> media.processOne(id)).code())
                .isEqualTo("MEDIA_CLAIM_EXPIRED");
        assertThat(media.get(id).get("status")).isEqualTo("PROCESSING");
        assertThat(media.get(id).get("sha256")).isNull();
        assertScratchEmpty();
    }

    @Test
    void twoInstancesCannotInspectTheSameLiveLease() throws Exception {
        String id = upload("MP4", resource("training.mp4"));
        media.completeUpload(id);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        storage.beforeDownload =
                () -> {
                    claimed.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Test did not release download");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> media.processOne(id));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            MediaService other = service();
            assertThat(assertThrows(BusinessException.class, () -> other.processOne(id)).code())
                    .isEqualTo("MEDIA_BUSY");
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            assertThat(media.get(id).get("status")).isEqualTo("READY");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void renamedInvalidPdfIsRejectedWithoutPublishingAHash() {
        String id = upload("PDF", new byte[] {1, 2, 3, 4, 5});
        media.completeUpload(id);
        media.processOne(id);
        assertThat(media.get(id).get("status")).isEqualTo("REJECTED");
        assertThat(media.get(id).get("sha256")).isNull();
        assertThrows(BusinessException.class, () -> media.ticket(id, null));
    }

    private MediaService service() throws IOException {
        MediaService result =
                new MediaService(
                        fixture.store,
                        fixture.transactions,
                        directory.toString(),
                        "https://api.example",
                        "ffprobe",
                        "pdftoppm",
                        "pdfinfo",
                        524288000);
        result.setObjectStorage(storage);
        return result;
    }

    private String upload(String type, byte[] bytes) {
        Map<String, Object> created =
                media.create(Data.map("title", "材料", "type", type, "size", bytes.length), "admin");
        storage.objects.put(storage.lastUploadKey, bytes.clone());
        return Data.str(created, "id");
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = new ClassPathResource("media/" + name).getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private void assertScratchEmpty() throws IOException {
        try (var files = Files.list(directory.resolve("files"))) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
