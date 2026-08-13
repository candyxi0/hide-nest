package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Single frozen canonical hasher for the Local V1 closeout content binding.
 *
 * <p>Production code and tests must both call this class; no second hand-written algorithm is
 * allowed. Every field is encoded with an unambiguous length prefix:
 * {@code UTF8(byteLength) + ':' + UTF8(value)}. null is {@code -1:}; booleans are {@code true} /
 * {@code false}; UUIDs are lowercase-hyphenated text; times are UTC ISO-8601 ({@code
 * OffsetDateTime.toInstant().toString()}); integers are decimal text; arrays write their element
 * count first, then each element in frozen request order.</p>
 */
public final class LocalV1CloseoutCanonicalizer {

    private LocalV1CloseoutCanonicalizer() {}

    /**
     * Thread-reader manifest hash. Covers schemaVersion, fromOrdinal, toOrdinal, continuous and the
     * selected evidence messages (sourceUnitId, actorId, ordinal, externalUnitRef, UTC occurredAt,
     * bodyHash). It must not include message body text (already bound by bodyHash).
     */
    public static String threadManifestHash(LocalV1CloseoutSubmission.ThreadReaderManifest manifest) {
        StringBuilder sb = new StringBuilder();
        field(sb, manifest.schemaVersion());
        number(sb, manifest.fromOrdinal());
        number(sb, manifest.toOrdinal());
        field(sb, manifest.continuous() ? "true" : "false");
        number(sb, manifest.selectedEvidenceMessages().size());
        for (var message : manifest.selectedEvidenceMessages()) {
            field(sb, message.sourceUnitId().toString());
            field(sb, message.actorId().toString());
            number(sb, message.ordinal());
            field(sb, message.externalUnitRef());
            field(sb, message.occurredAt().toInstant().toString());
            field(sb, message.bodyHash());
        }
        return sha256Hex(sb.toString());
    }

    /**
     * Review manifest hash. Covers submissionId, threadId, hideSelection (perspectiveActorId,
     * memoryType, bodyHash), the recomputed thread manifest hash, the source anchors and the
     * userConfirmation decision. It must not include reviewManifestHash itself,
     * confirmationSourceUnitId or confirmationProof.
     */
    public static String reviewManifestHash(LocalV1CloseoutSubmission request) {
        StringBuilder sb = new StringBuilder();
        field(sb, request.submissionId().toString());
        field(sb, request.threadId().toString());
        field(sb, request.hideSelection().perspectiveActorId().toString());
        field(sb, request.hideSelection().memoryType());
        field(sb, request.hideSelection().bodyHash());
        field(sb, threadManifestHash(request.threadReaderManifest()));
        number(sb, request.sourceAnchors().size());
        for (var anchor : request.sourceAnchors()) {
            field(sb, anchor.anchorId().toString());
            number(sb, anchor.units().size());
            for (var unit : anchor.units()) {
                field(sb, unit.sourceUnitId().toString());
                nullableNumber(sb, unit.fromOffset());
                nullableNumber(sb, unit.toOffset());
                number(sb, unit.ordinal());
            }
        }
        field(sb, request.userConfirmation().decision());
        return sha256Hex(sb.toString());
    }

    /** Frozen Task31A confirmation proof formula. */
    public static String confirmationProof(
            UUID threadId, UUID confirmationSourceUnitId, String reviewManifestHash, UUID submissionId) {
        return sha256Hex(threadId
                + "\n"
                + confirmationSourceUnitId
                + "\n"
                + reviewManifestHash
                + "\n"
                + submissionId);
    }

    // ── length-prefixed encoding ──────────────────────────────────────────

    private static void field(StringBuilder sb, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sb.append(bytes.length).append(':').append(value);
    }

    private static void number(StringBuilder sb, long value) {
        field(sb, Long.toString(value));
    }

    private static void nullableNumber(StringBuilder sb, Long value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            number(sb, value);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
