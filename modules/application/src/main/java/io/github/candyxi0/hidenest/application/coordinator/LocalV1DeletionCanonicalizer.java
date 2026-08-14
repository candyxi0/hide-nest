package io.github.candyxi0.hidenest.application.coordinator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Single frozen canonical hasher for the Local V1 deletion request binding.
 *
 * <p>Production code and tests must both call this class; no second hand-written algorithm is
 * allowed. The frozen inputs are {@code LOCAL_V1_DELETE_PREVIEW_V1}, the target memory id (lowercase
 * hyphenated UUID text), the expected revision (decimal) and the expected policy revision (decimal).
 * Each field is encoded with the same length-prefix idea used by the closeout canonicalizer:
 * {@code UTF8(byteLength) + ':' + UTF8(value)}, then SHA-256 produces the 32-byte request hash.</p>
 */
public final class LocalV1DeletionCanonicalizer {

    private static final String VERSION_LABEL = "LOCAL_V1_DELETE_PREVIEW_V1";

    private LocalV1DeletionCanonicalizer() {}

    /** 32-byte request hash fed to {@code LocalV1S3ADeletionPreviewRequest.requestHash}. */
    public static byte[] requestHash(UUID targetId, long expectedRevision, long expectedPolicyRevision) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, VERSION_LABEL);
        field(canonical, targetId.toString());
        field(canonical, Long.toString(expectedRevision));
        field(canonical, Long.toString(expectedPolicyRevision));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String requestHashHex(UUID targetId, long expectedRevision, long expectedPolicyRevision) {
        return bytesToHex(requestHash(targetId, expectedRevision, expectedPolicyRevision));
    }

    public static boolean is64LowerHex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }

    /** Constant-time comparison of a 64-char lowercase hex string against a byte array. */
    public static boolean constantTimeEqualsHex(String hex, byte[] bytes) {
        if (!is64LowerHex(hex)) {
            return false;
        }
        return MessageDigest.isEqual(
                hex.getBytes(StandardCharsets.UTF_8), bytesToHex(bytes).getBytes(StandardCharsets.UTF_8));
    }

    private static void field(StringBuilder sb, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sb.append(bytes.length).append(':').append(value);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
