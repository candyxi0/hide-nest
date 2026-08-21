package io.github.candyxi0.hidenest.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict opaque seek cursor token. It carries no total, accepts one canonical encoding only, and is
 * bound to the canonical filter (state / normalized keyword / memoryType) that produced it.
 *
 * <p>Payload: {@code local-v1-memory-seek-v1:<micros>:<uuid>:<filterFingerprint>:<integrityDigest>}
 * where {@code filterFingerprint} is the full SHA-256 (64 hex) of the canonical filter and
 * {@code integrityDigest} is the truncated SHA-256 (16 hex) of the preceding payload.
 */
final class LocalV1CursorCodec {

    private static final int MAX_CURSOR_CHARS = 256;
    private static final String VERSION = "local-v1-memory-seek-v1";
    private static final Pattern PAYLOAD = Pattern.compile(
            "^local-v1-memory-seek-v1:(0|[1-9][0-9]{0,18}):"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):"
                    + "([0-9a-f]{64}):([0-9a-f]{16})$");

    private LocalV1CursorCodec() {}

    /** Decoded cursor: the exact seek position past the previous page's last item. */
    record Decoded(long lastUpdatedAtMicros, UUID lastMemoryId) {}

    static String encode(long lastUpdatedAtMicros, UUID lastMemoryId, String canonicalFilter) {
        if (lastUpdatedAtMicros < 0 || lastMemoryId == null) {
            throw new IllegalArgumentException("cursor");
        }
        String prefix = VERSION + ":" + lastUpdatedAtMicros + ":" + lastMemoryId + ":"
                + filterFingerprint(canonicalFilter);
        String payload = prefix + ":" + digest(prefix);
        return encodePayload(payload);
    }

    static Decoded decode(String cursor, String canonicalFilter) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_CHARS) {
            throw new IllegalArgumentException("cursor");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String payload = new String(decoded, StandardCharsets.US_ASCII);
            if (!encodePayload(payload).equals(cursor)) {
                throw new IllegalArgumentException("cursor");
            }
            Matcher matcher = PAYLOAD.matcher(payload);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("cursor");
            }
            String prefix = VERSION + ":" + matcher.group(1) + ":" + matcher.group(2) + ":"
                    + matcher.group(3);
            if (!MessageDigest.isEqual(
                    digest(prefix).getBytes(StandardCharsets.US_ASCII),
                    matcher.group(4).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("cursor");
            }
            if (!MessageDigest.isEqual(
                    filterFingerprint(canonicalFilter).getBytes(StandardCharsets.US_ASCII),
                    matcher.group(3).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("cursor");
            }
            return new Decoded(Long.parseLong(matcher.group(1)), UUID.fromString(matcher.group(2)));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor", exception);
        }
    }

    /**
     * Unambiguous canonical filter encoding: length-prefixed segments for state, normalized keyword
     * and memoryType-or-ALL, so arbitrary keyword characters cannot collide across field boundaries.
     */
    static String encodeFilterCanonical(String state, String keyword, String memoryTypeOrAll) {
        return segment(state) + segment(keyword) + segment(memoryTypeOrAll);
    }

    static String filterFingerprint(String canonicalFilter) {
        return HexFormat.of().formatHex(digestBytes(canonicalFilter));
    }

    private static String segment(String value) {
        String v = value == null ? "" : value;
        return v.length() + ":" + v;
    }

    private static String digest(String prefix) {
        return HexFormat.of().formatHex(digestBytes(prefix), 0, 8);
    }

    private static byte[] digestBytes(String value) {
        try {
            // UTF-8 so Chinese/emoji in the canonical filter are hashed byte-for-byte; the integrity
            // prefix is ASCII, for which UTF-8 is identical.
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }
}
