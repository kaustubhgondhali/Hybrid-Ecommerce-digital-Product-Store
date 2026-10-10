package com.hybrid.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Object-storage abstraction. PUBLIC objects are served at /media/**; PRIVATE objects are never served
 * directly and are only streamed after an entitlement check. A local-disk implementation is provided;
 * an S3-compatible implementation can replace it without changing callers.
 */
public interface FileStorage {

    enum Area { PUBLIC, PRIVATE }

    record StoredFile(String key, long sizeBytes, String sha256) {
    }

    StoredFile store(Area area, InputStream content, String extension, long maxBytes) throws IOException;

    Path resolve(Area area, String key);

    void delete(Area area, String key);
}
