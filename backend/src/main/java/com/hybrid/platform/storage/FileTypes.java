package com.hybrid.platform.storage;

import com.hybrid.platform.common.error.ApiException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** File-type checks based on magic bytes, not on the client-supplied Content-Type. */
public final class FileTypes {

    private static final Set<String> DIGITAL_EXTENSIONS = Set.of(
            "pdf", "epub", "zip", "mp3", "mp4", "m4a", "wav", "png", "jpg", "jpeg", "webp", "txt", "csv",
            "docx", "xlsx", "pptx", "psd", "ai", "svg", "ttf", "otf", "woff2", "mobi", "json");

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "sh", "dll", "html", "htm");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"), Map.entry("epub", "application/epub+zip"),
            Map.entry("zip", "application/zip"), Map.entry("mp3", "audio/mpeg"), Map.entry("mp4", "video/mp4"),
            Map.entry("m4a", "audio/mp4"), Map.entry("wav", "audio/wav"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("webp", "image/webp"),
            Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"), Map.entry("json", "application/json"));

    private FileTypes() {
    }

    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Returns the canonical image extension if the header bytes are PNG/JPEG/WEBP/GIF, otherwise rejects. */
    public static String requireImage(byte[] header) {
        if (startsWith(header, 0x89, 0x50, 0x4E, 0x47)) return "png";
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) return "jpg";
        if (startsWith(header, 0x47, 0x49, 0x46, 0x38)) return "gif";
        if (header.length >= 12 && startsWith(header, 0x52, 0x49, 0x46, 0x46)
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return "webp";
        throw ApiException.rule("INVALID_IMAGE", "Only PNG, JPEG, WEBP or GIF images are allowed");
    }

    public static String requireDigitalFile(String filename, byte[] header) {
        String ext = extension(filename);
        if (ext.isEmpty() || BLOCKED_EXTENSIONS.contains(ext) || !DIGITAL_EXTENSIONS.contains(ext)) {
            throw ApiException.rule("FILE_TYPE_NOT_ALLOWED", "This file type is not allowed for digital products");
        }
        boolean windowsExecutable = startsWith(header, 0x4D, 0x5A);
        boolean elf = startsWith(header, 0x7F, 0x45, 0x4C, 0x46);
        if (windowsExecutable || elf) {
            throw ApiException.rule("FILE_TYPE_NOT_ALLOWED", "Executable files are not allowed");
        }
        if (ext.equals("pdf") && !startsWith(header, 0x25, 0x50, 0x44, 0x46)) {
            throw ApiException.rule("FILE_CONTENT_MISMATCH", "File content does not match its .pdf extension");
        }
        if (Set.of("zip", "epub", "docx", "xlsx", "pptx").contains(ext) && !startsWith(header, 0x50, 0x4B)) {
            throw ApiException.rule("FILE_CONTENT_MISMATCH", "File content does not match its extension");
        }
        return ext;
    }

    public static String contentType(String ext) {
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        byte[] expected = new byte[prefix.length];
        for (int i = 0; i < prefix.length; i++) {
            expected[i] = (byte) prefix[i];
        }
        return Arrays.equals(Arrays.copyOf(data, prefix.length), expected);
    }
}
