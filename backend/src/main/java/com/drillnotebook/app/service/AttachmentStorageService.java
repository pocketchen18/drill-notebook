package com.drillnotebook.app.service;

import com.drillnotebook.app.config.PortablePathResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AttachmentStorageService {
    private final PortablePathResolver paths;

    public AttachmentStorageService(PortablePathResolver paths) { this.paths = paths; }

    public StoreResult store(long pageId, String fileName, InputStream input) throws IOException {
        Path attachmentsRoot = paths.data().resolve("attachments").resolve(String.valueOf(pageId));
        Files.createDirectories(attachmentsRoot);
        String extension = extractExtension(fileName);
        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = attachmentsRoot.resolve(storedName);

        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }

        long totalBytes;
        try (var digestStream = new DigestInputStream(input, digest)) {
            totalBytes = Files.copy(digestStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String sha256 = bytesToHex(digest.digest());
        String mimeType = guessMimeType(fileName);
        String storagePath = "attachments/" + pageId + "/" + storedName;
        return new StoreResult(storagePath, sha256, mimeType, totalBytes);
    }

    public InputStream openInputStream(String storagePath) throws IOException {
        return Files.newInputStream(resolveAbsolutePath(storagePath));
    }

    public long fileSize(String storagePath) throws IOException {
        return Files.size(resolveAbsolutePath(storagePath));
    }

    public void deleteFile(String storagePath) throws IOException {
        Files.deleteIfExists(resolveAbsolutePath(storagePath));
    }

    public Path resolveAbsolutePath(String storagePath) {
        return paths.data().resolve(storagePath).normalize();
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".rar")) return "application/x-rar-compressed";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";
        return "application/octet-stream";
    }

    private static String bytesToHex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    public record StoreResult(String storagePath, String sha256, String mimeType, long fileSize) {}
}
