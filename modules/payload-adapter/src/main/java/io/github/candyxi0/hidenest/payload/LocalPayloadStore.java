package io.github.candyxi0.hidenest.payload;

import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Local file-system implementation of {@link PayloadStore}. */
public class LocalPayloadStore implements PayloadStore {

    private static final long MAX_BYTES = 1024 * 1024; // 1 MiB
    private static final String STORE_ADAPTER = "LOCAL_FILE";
    private static final Pattern OBJECT_REF_PATTERN =
            Pattern.compile("[0-9a-f]{2}/[0-9a-f]{32}\\.payload");

    private final Path root;
    private final ConcurrentHashMap<String, Object> mutexes = new ConcurrentHashMap<>();

    /** @param root controlled storage root; must already exist or be creatable */
    public LocalPayloadStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    // ── PayloadStore ──────────────────────────────────────────────────────

    @Override
    public PayloadPutResult put(UUID payloadId, String contentType, byte[] bytes, byte[] expectedHash) {
        if (payloadId == null) {
            throw new PayloadStoreException("INVALID_REF", "payloadId must not be null");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new PayloadStoreException("INVALID_REF", "contentType must not be blank");
        }
        byte[] bodyBytes = bytes.clone();
        if (expectedHash == null || expectedHash.length != 32) {
            throw new PayloadStoreException("HASH_MISMATCH", "expectedHash must be 32 bytes");
        }
        if (bodyBytes.length > MAX_BYTES) {
            throw new PayloadStoreException("SIZE_EXCEEDED",
                    "payload size " + bodyBytes.length + " exceeds max " + MAX_BYTES);
        }
        byte[] actualHash = sha256(bodyBytes);
        if (!Arrays.equals(actualHash, expectedHash)) {
            throw new PayloadStoreException("HASH_MISMATCH",
                    "computed hash does not match expectedHash");
        }

        String objectRef = buildObjectRef(payloadId);
        Path filePath = resolveChecked(objectRef);

        Object mutex = mutexes.computeIfAbsent(objectRef, k -> new Object());
        synchronized (mutex) {
            if (Files.exists(filePath)) {
                byte[] existingBody = readBodyFromFile(filePath);
                byte[] existingHash = sha256(existingBody);
                if (Arrays.equals(existingHash, expectedHash)) {
                    return new PayloadPutResult(
                            payloadId,
                            objectRef,
                            existingBody.length,
                            existingHash.clone(),
                            STORE_ADAPTER,
                            false);
                }
                throw new PayloadStoreException("CONFLICT",
                        "same objectRef with different content");
            }

            byte[] fileContent = encodeFile(contentType, bodyBytes);
            Path parent = filePath.getParent();
            try {
                Files.createDirectories(parent);
                ensureNoSymbolicLinkInExistingPath(parent);
            } catch (IOException e) {
                throw new PayloadStoreException("STORE_ERROR", "cannot create directory", e);
            }

            Path tmpFile = parent.resolve(
                    "." + payloadId.toString().replace("-", "") + ".tmp." + UUID.randomUUID());
            try {
                Files.write(tmpFile, fileContent);
                Files.move(tmpFile, filePath, StandardCopyOption.ATOMIC_MOVE);
                return new PayloadPutResult(
                        payloadId,
                        objectRef,
                        bodyBytes.length,
                        actualHash.clone(),
                        STORE_ADAPTER,
                        true);
            } catch (IOException e) {
                cleanupTmp(tmpFile);
                throw new PayloadStoreException("STORE_ERROR", "write failed", e);
            }
        }
    }

    @Override
    public byte[] get(String objectRef, byte[] expectedHash, long maxBytes) {
        if (expectedHash == null || expectedHash.length != 32) {
            throw new PayloadStoreException("HASH_MISMATCH", "expectedHash must be 32 bytes");
        }
        Path filePath = resolveChecked(objectRef);
        ensureNoSymbolicLinkInExistingPath(filePath);
        if (!Files.exists(filePath)) {
            throw new PayloadStoreException("NOT_FOUND", "objectRef not found");
        }
        byte[] bodyBytes = readBodyFromFile(filePath);
        if (bodyBytes.length > maxBytes) {
            throw new PayloadStoreException("SIZE_EXCEEDED",
                    "payload size " + bodyBytes.length + " exceeds max " + maxBytes);
        }
        byte[] actualHash = sha256(bodyBytes);
        if (!Arrays.equals(actualHash, expectedHash)) {
            throw new PayloadStoreException("HASH_MISMATCH",
                    "content hash does not match expectedHash");
        }
        return bodyBytes; // already a defensive copy from readBodyFromFile
    }

    @Override
    public PayloadHeadResult head(String objectRef) {
        Path filePath = resolveChecked(objectRef);
        ensureNoSymbolicLinkInExistingPath(filePath);
        if (!Files.exists(filePath)) {
            throw new PayloadStoreException("NOT_FOUND", "objectRef not found");
        }
        return readHeadFromFile(filePath);
    }

    @Override
    public void delete(String objectRef, byte[] expectedHash) {
        if (expectedHash == null || expectedHash.length != 32) {
            throw new PayloadStoreException("HASH_MISMATCH", "expectedHash must be 32 bytes");
        }
        Path filePath = resolveChecked(objectRef);
        ensureNoSymbolicLinkInExistingPath(filePath);
        if (!Files.exists(filePath)) {
            throw new PayloadStoreException("NOT_FOUND", "objectRef not found");
        }
        byte[] bodyBytes = readBodyFromFile(filePath);
        byte[] actualHash = sha256(bodyBytes);
        if (!Arrays.equals(actualHash, expectedHash)) {
            throw new PayloadStoreException("HASH_MISMATCH",
                    "content hash does not match expectedHash");
        }
        try {
            Files.delete(filePath);
        } catch (IOException e) {
            throw new PayloadStoreException("STORE_ERROR", "delete failed", e);
        }
    }

    // ── internal ──────────────────────────────────────────────────────────

    private static String buildObjectRef(UUID payloadId) {
        String hex = payloadId.toString().replace("-", "");
        return hex.substring(0, 2) + "/" + hex + ".payload";
    }

    /** Resolve objectRef against root, with path-traversal protection. */
    private Path resolveChecked(String objectRef) {
        if (objectRef == null || !OBJECT_REF_PATTERN.matcher(objectRef).matches()) {
            throw new PayloadStoreException("INVALID_REF", "invalid objectRef format");
        }
        if (objectRef.contains("..") || objectRef.contains("\\")) {
            throw new PayloadStoreException("INVALID_REF", "objectRef contains illegal path segments");
        }
        Path resolved = root.resolve(objectRef).normalize();
        if (!resolved.startsWith(root)) {
            throw new PayloadStoreException("INVALID_REF", "objectRef escapes root");
        }
        return resolved;
    }

    private static void ensureNoSymbolicLinkInExistingPath(Path path) {
        Path current = path.getRoot();
        for (Path name : path) {
            current = current == null ? name : current.resolve(name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new PayloadStoreException("INVALID_REF", "path contains symbolic link");
            }
        }
    }

    // ── file format ───────────────────────────────────────────────────────
    // [2 bytes BE: ctLen][ctLen bytes: contentType UTF-8][body bytes]

    private static byte[] encodeFile(String contentType, byte[] bodyBytes) {
        byte[] ctBytes = contentType.getBytes(StandardCharsets.UTF_8);
        if (ctBytes.length > 65535) {
            throw new PayloadStoreException("INVALID_REF", "contentType too long");
        }
        byte[] fileContent = new byte[2 + ctBytes.length + bodyBytes.length];
        fileContent[0] = (byte) (ctBytes.length >> 8);
        fileContent[1] = (byte) (ctBytes.length & 0xFF);
        System.arraycopy(ctBytes, 0, fileContent, 2, ctBytes.length);
        System.arraycopy(bodyBytes, 0, fileContent, 2 + ctBytes.length, bodyBytes.length);
        return fileContent;
    }

    private static byte[] readBodyFromFile(Path filePath) {
        byte[] fileContent;
        try {
            fileContent = Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new PayloadStoreException("STORE_ERROR", "read failed", e);
        }
        if (fileContent.length < 2) {
            throw new PayloadStoreException("STORE_ERROR", "file too short for header");
        }
        int ctLen = ((fileContent[0] & 0xFF) << 8) | (fileContent[1] & 0xFF);
        if (2 + ctLen > fileContent.length) {
            throw new PayloadStoreException("STORE_ERROR", "header exceeds file length");
        }
        return Arrays.copyOfRange(fileContent, 2 + ctLen, fileContent.length);
    }

    private static PayloadHeadResult readHeadFromFile(Path filePath) {
        byte[] fileContent;
        try {
            fileContent = Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new PayloadStoreException("STORE_ERROR", "read failed", e);
        }
        if (fileContent.length < 2) {
            throw new PayloadStoreException("STORE_ERROR", "file too short for header");
        }
        int ctLen = ((fileContent[0] & 0xFF) << 8) | (fileContent[1] & 0xFF);
        if (2 + ctLen > fileContent.length) {
            throw new PayloadStoreException("STORE_ERROR", "header exceeds file length");
        }
        String contentType = new String(fileContent, 2, ctLen, StandardCharsets.UTF_8);
        byte[] bodyBytes = Arrays.copyOfRange(fileContent, 2 + ctLen, fileContent.length);
        byte[] bodyHash = sha256(bodyBytes);
        return new PayloadHeadResult(bodyBytes.length, bodyHash, contentType);
    }

    // ── crypto ────────────────────────────────────────────────────────────

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new PayloadStoreException("STORE_ERROR", "SHA-256 unavailable", e);
        }
    }

    private static void cleanupTmp(Path tmpFile) {
        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException ignored) {
        }
    }
}
