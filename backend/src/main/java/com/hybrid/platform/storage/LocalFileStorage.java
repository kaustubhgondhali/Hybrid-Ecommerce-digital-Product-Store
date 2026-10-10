package com.hybrid.platform.storage;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern SAFE_KEY = Pattern.compile("^[0-9]{4}/[0-9]{2}/[a-f0-9-]{36}(\\.[a-z0-9]{1,8})?$");

    private final Path root;

    public LocalFileStorage(AppProperties props) throws IOException {
        this.root = Path.of(props.storage().root()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("public"));
        Files.createDirectories(root.resolve("private"));
    }

    public Path publicRoot() {
        return root.resolve("public");
    }

    @Override
    public StoredFile store(Area area, InputStream content, String extension, long maxBytes) throws IOException {
        LocalDate today = LocalDate.now();
        String ext = extension == null || extension.isBlank() ? "" : "." + extension.toLowerCase();
        String key = "%04d/%02d/%s%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID(), ext);
        Path target = areaRoot(area).resolve(key);
        Files.createDirectories(target.getParent());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long written = 0;
        try (InputStream in = new DigestInputStream(content, digest); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw ApiException.rule("FILE_TOO_LARGE", "The uploaded file is too large");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new StoredFile(key, written, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public Path resolve(Area area, String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw ApiException.notFound("FILE_NOT_FOUND", "File not found");
        }
        Path base = areaRoot(area);
        Path path = base.resolve(key).normalize();
        if (!path.startsWith(base)) {
            throw ApiException.notFound("FILE_NOT_FOUND", "File not found");
        }
        return path;
    }

    @Override
    public void delete(Area area, String key) {
        try {
            Files.deleteIfExists(resolve(area, key));
        } catch (IOException ignored) {
            // best effort: orphaned files are harmless and can be cleaned up offline
        }
    }

    private Path areaRoot(Area area) {
        return root.resolve(area == Area.PUBLIC ? "public" : "private");
    }
}
