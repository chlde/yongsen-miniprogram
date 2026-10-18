package com.yongsen.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Byte-preserving COS test double. It enforces conditional copy and exact transfer lengths. */
final class FakeMediaObjectStorage implements MediaObjectStorage {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger copies = new AtomicInteger();
    final AtomicInteger heads = new AtomicInteger();
    final Map<String, String> archiveETags = new ConcurrentHashMap<>();
    Consumer<String> beforeStat = key -> {};
    Runnable beforeCopy = () -> {};
    Runnable afterCopy = () -> {};
    Runnable beforeDownload = () -> {};
    RuntimeException putFailure;
    String lastUploadKey;
    Instant lastDownloadExpiry;
    String lastDownloadKey;

    @Override
    public Upload createUpload(
            String key, String contentType, long expectedBytes, Instant expiresAt) {
        lastUploadKey = key;
        return new Upload(
                "https://private.example/" + key + "?signature=test",
                "PUT",
                Map.of("Content-Type", contentType));
    }

    @Override
    public Metadata stat(String key) {
        return find(key)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        "UPLOAD_INCOMPLETE", "Object not uploaded", 422));
    }

    @Override
    public Optional<Metadata> find(String key) {
        beforeStat.accept(key);
        heads.incrementAndGet();
        byte[] value = objects.get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(new Metadata(value.length, eTag(value), archiveETags.get(key)));
    }

    @Override
    public void copyIfMatch(String source, String target, String eTag) {
        beforeCopy.run();
        synchronized (objects) {
            byte[] value = objects.get(source);
            if (value == null || !eTag(value).equals(eTag)) {
                throw new BusinessException("UPLOAD_SOURCE_CHANGED", "上传文件在确认后被修改或移除，请重新上传", 422);
            }
            objects.put(target, Arrays.copyOf(value, value.length));
            archiveETags.put(target, eTag);
            copies.incrementAndGet();
        }
        afterCopy.run();
    }

    @Override
    public void download(String key, Path destination, long expectedBytes) throws IOException {
        beforeDownload.run();
        byte[] value = objects.get(key);
        Data.require(
                value != null && value.length == expectedBytes,
                "UPLOAD_SIZE_MISMATCH",
                "Incomplete object");
        Files.write(destination, value);
    }

    @Override
    public void put(String key, Path source, String contentType) throws IOException {
        if (putFailure != null) {
            throw putFailure;
        }
        objects.put(key, Files.readAllBytes(source));
    }

    @Override
    public String signedGet(String key, String contentType, Instant expiresAt) {
        lastDownloadKey = key;
        lastDownloadExpiry = expiresAt;
        return "https://private.example/" + key + "?signature=read";
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        archiveETags.remove(key);
    }

    private static String eTag(byte[] value) {
        return Data.hash(java.util.HexFormat.of().formatHex(value));
    }
}
