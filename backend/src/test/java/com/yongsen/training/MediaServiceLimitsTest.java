package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class MediaServiceLimitsTest {
    private static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;
    private static final long PDF_MAX_BYTES = 50L * 1024 * 1024;
    private static final long DEPLOYMENT_CAP_BYTES = 8L * 1024 * 1024;

    @TempDir private Path directory;
    private BackendFixture fixture;

    @BeforeEach
    void setup() {
        fixture = new BackendFixture();
    }

    @Test
    void maximumDeploymentCapStillPreservesTheSmallerPdfLimit() throws IOException {
        MediaService media = media(VIDEO_MAX_BYTES);
        assertEquals(VIDEO_MAX_BYTES, media.uploadLimits().get("videoMaxBytes"));
        assertEquals(PDF_MAX_BYTES, media.uploadLimits().get("pdfMaxBytes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MP4", "PDF"})
    void oneByteAboveDeploymentCapIsRejectedBeforeAnyWrite(String type) throws IOException {
        MediaService media = media(DEPLOYMENT_CAP_BYTES);
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                media.create(
                                        request(type, DEPLOYMENT_CAP_BYTES + 1), "administrator"));
        assertEquals("FILE_TOO_LARGE", error.code());
        assertEquals(422, error.status());
        assertEquals(before, databaseSnapshot());
        try (Stream<Path> files = Files.list(directory.resolve("files"))) {
            assertEquals(0, files.count());
        }
    }

    @ParameterizedTest
    @CsvSource({"MP4,524288001", "PDF,52428801"})
    void typeSpecificMaximumIsEnforcedEvenWithTheLargestDeploymentCap(String type, long size)
            throws IOException {
        MediaService media = media(VIDEO_MAX_BYTES);
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> media.create(request(type, size), "administrator"));
        assertEquals("FILE_TOO_LARGE", error.code());
        assertEquals(before, databaseSnapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MP4", "PDF"})
    void exactDeploymentCapIsAcceptedAsUploadMetadataWithoutAllocatingTheFile(String type)
            throws IOException {
        MediaService media = media(DEPLOYMENT_CAP_BYTES);
        Map<String, Object> result =
                media.create(request(type, DEPLOYMENT_CAP_BYTES), "administrator");
        Map<String, Object> stored =
                fixture.store
                        .get(Store.Table.MATERIAL, Data.str(result, "materialId"))
                        .toBoundary();
        assertEquals("UPLOADING", stored.get("status"));
        assertEquals(DEPLOYMENT_CAP_BYTES, Data.num(stored, "size", 0));
        assertFalse(Data.bool(stored, "uploaded"));
        assertEquals(1, fixture.store.all(Store.Table.MATERIAL).size());
        assertEquals(1, fixture.store.all(Store.Table.AUDIT_LOG).size());
        assertTrue(
                Data.str(result, "uploadUrl")
                        .startsWith("https://media.example/api/v1/media/uploads/"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void emptyAndNegativeDeclaredSizesDoNotCreateMaterials(long size) throws IOException {
        MediaService media = media(DEPLOYMENT_CAP_BYTES);
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> media.create(request("MP4", size), "administrator"));
        assertEquals("FILE_TOO_LARGE", error.code());
        assertEquals(before, databaseSnapshot());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 524288001})
    void invalidDeploymentCapFailsBeforeDatabaseOrFilesystemInitialization(long cap) {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        assertThrows(IllegalArgumentException.class, () -> media(cap));
        assertEquals(before, databaseSnapshot());
        assertFalse(Files.exists(directory.resolve("files")));
    }

    private MediaService media(long cap) throws IOException {
        return new MediaService(
                fixture.store,
                fixture.transactions,
                directory.toString(),
                "https://media.example",
                "unused-ffprobe",
                "unused-pdftoppm",
                "unused-pdfinfo",
                cap);
    }

    private Map<String, Object> request(String type, long size) {
        return Data.map("title", "上传边界测试", "type", type, "size", size);
    }

    private Map<Store.Table, List<Map<String, Object>>> databaseSnapshot() {
        Map<Store.Table, List<Map<String, Object>>> snapshot = new EnumMap<>(Store.Table.class);
        for (Store.Table table : Store.Table.values()) {
            snapshot.put(
                    table,
                    fixture.store.all(table).stream().map(StoredRecordDO::toBoundary).toList());
        }
        return snapshot;
    }
}
