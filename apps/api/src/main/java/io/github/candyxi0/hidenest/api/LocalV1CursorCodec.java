package io.github.candyxi0.hidenest.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict opaque offset token. It carries no total and accepts one canonical encoding only. */
final class LocalV1CursorCodec {

    private static final Pattern PAYLOAD = Pattern.compile("^local-v1-offset:(0|[1-9][0-9]{0,8}):([0-9a-f]{16})$");

    private LocalV1CursorCodec() {}

    static String encode(int offset) {
        if (offset < 0) throw new IllegalArgumentException("offset");
        String prefix = "local-v1-offset:" + offset;
        String payload = prefix + ":" + digest(prefix);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    static int decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > 128) {
            throw new IllegalArgumentException("cursor");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String payload = new String(decoded, StandardCharsets.US_ASCII);
            if (!encodePayload(payload).equals(cursor)) throw new IllegalArgumentException("cursor");
            Matcher matcher = PAYLOAD.matcher(payload);
            if (!matcher.matches()) throw new IllegalArgumentException("cursor");
            String prefix = "local-v1-offset:" + matcher.group(1);
            if (!MessageDigest.isEqual(
                    digest(prefix).getBytes(StandardCharsets.US_ASCII),
                    matcher.group(2).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("cursor");
            }
            return Integer.parseInt(matcher.group(1));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor", exception);
        }
    }

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
