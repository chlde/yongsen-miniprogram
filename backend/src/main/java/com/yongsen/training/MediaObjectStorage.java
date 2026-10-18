package com.yongsen.training;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Private object operations. Implementations never accept a client-selected bucket or object key.
 */
public interface MediaObjectStorage {
    record Upload(String url, String method, Map<String, String> headers) {}

    record Metadata(long size, String eTag, String archiveSourceETag) {}

    /** Authorizes a short-lived upload to a server-generated staging key. */
    Upload createUpload(String key, String contentType, long expectedBytes, Instant expiresAt);

    /** Reads the authoritative size and version token of an existing object. */
    Metadata stat(String key);

    /** Returns empty only for a missing object; unavailable storage remains a retryable failure. */
    Optional<Metadata> find(String key);

    /**
     * Copies only the inspected version into a distinct server-only key, replacing its metadata
     * with a source ETag marker. UPLOAD_SOURCE_CHANGED rejects a changed/missing source;
     * CLOUD_STORAGE_UNAVAILABLE is retryable and may mean that copying succeeded without a reply.
     */
    void copyIfMatch(String source, String target, String eTag);

    /** Downloads at most expectedBytes and fails if the object is shorter or longer. */
    void download(String key, Path destination, long expectedBytes) throws IOException;

    /** Uploads a worker-produced file under a unique server-only key. */
    void put(String key, Path source, String contentType) throws IOException;

    /** Authorizes GET with optional Range; headers must not freeze a specific byte range. */
    String signedGet(String key, String contentType, Instant expiresAt);

    /** Deletes a known application object, for cleaning abandoned or failed work only. */
    void delete(String key);
}
