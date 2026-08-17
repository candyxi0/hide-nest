package io.github.candyxi0.hidenest.application.coordinator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Single frozen canonicalizer for the Local V1 CandidateSet CREATE projection.
 *
 * <p>It derives the per-candidate policy identity, publish idempotency key, request hash and
 * manifest hash exclusively from {@code candidateSetId + candidateId + persisted normative fields}.
 * The body text itself never enters any hash or manifest, but the body fact is bound by its
 * 32-byte SHA-256 {@code bodyHash}: a body/bodyHash pair that is swapped together therefore no
 * longer reuses the original projection identity. Encoding is unambiguous length-prefixed UTF-8
 * ({@code UTF8(byteLength) + ':' + UTF8(value)}); null is {@code -1:} and the empty value is
 * {@code 0:} — never equal. {@code bodyHash} is encoded as stable lowercase hex, so null, the
 * empty array and any 32-byte value are pairwise distinct. Production code and tests must both
 * call this class; no second hand-written algorithm is allowed.</p>
 */
public final class LocalV1CandidateSetProjectionCanonicalizer {

    private LocalV1CandidateSetProjectionCanonicalizer() {}

    /** Deterministic access-policy id for one candidate's CREATE memory. */
    public static UUID policyId(UUID candidateSetId, UUID candidateId) {
        return UUID.nameUUIDFromBytes(
                ("candidate-set:publish:policy:" + candidateSetId + ":" + candidateId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** Deterministic per-candidate publish idempotency key (stable across replay). */
    public static String publishIdempotencyKey(UUID candidateSetId, UUID candidateId) {
        return "candidate-set:publish:" + candidateSetId + ":" + candidateId;
    }

    /** Body-free request hash for idempotency and conflict detection. */
    public static byte[] requestHash(
            UUID candidateSetId,
            UUID candidateId,
            long ordinal,
            UUID decisionId,
            UUID proposalRevisionId,
            UUID reviewSessionId,
            UUID memoryId,
            String memoryType,
            UUID perspectiveActorId,
            byte[] bodyHash,
            List<UUID> anchorIds) {
        StringBuilder sb = new StringBuilder();
        field(sb, "request");
        bind(sb, candidateSetId, candidateId, ordinal, decisionId, proposalRevisionId,
                reviewSessionId, memoryId, memoryType, perspectiveActorId, bodyHash, anchorIds);
        return sha256(sb.toString());
    }

    /** Body-free manifest hash used in change-event detail and outbox payload manifests. */
    public static byte[] manifestHash(
            UUID candidateSetId,
            UUID candidateId,
            long ordinal,
            UUID decisionId,
            UUID proposalRevisionId,
            UUID reviewSessionId,
            UUID memoryId,
            String memoryType,
            UUID perspectiveActorId,
            byte[] bodyHash,
            List<UUID> anchorIds) {
        StringBuilder sb = new StringBuilder();
        field(sb, "manifest");
        bind(sb, candidateSetId, candidateId, ordinal, decisionId, proposalRevisionId,
                reviewSessionId, memoryId, memoryType, perspectiveActorId, bodyHash, anchorIds);
        return sha256(sb.toString());
    }

    private static void bind(
            StringBuilder sb,
            UUID candidateSetId,
            UUID candidateId,
            long ordinal,
            UUID decisionId,
            UUID proposalRevisionId,
            UUID reviewSessionId,
            UUID memoryId,
            String memoryType,
            UUID perspectiveActorId,
            byte[] bodyHash,
            List<UUID> anchorIds) {
        uuidField(sb, candidateSetId);
        uuidField(sb, candidateId);
        number(sb, ordinal);
        uuidField(sb, decisionId);
        uuidField(sb, proposalRevisionId);
        uuidField(sb, reviewSessionId);
        uuidField(sb, memoryId);
        field(sb, memoryType);
        uuidField(sb, perspectiveActorId);
        hashField(sb, bodyHash);
        number(sb, anchorIds.size());
        for (UUID anchorId : anchorIds) {
            uuidField(sb, anchorId);
        }
    }

    // ── length-prefixed UTF-8 encoding ────────────────────────────────────

    private static void field(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:");
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sb.append(bytes.length).append(':').append(value);
    }

    private static void number(StringBuilder sb, long value) {
        field(sb, Long.toString(value));
    }

    private static void uuidField(StringBuilder sb, UUID value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            field(sb, value.toString());
        }
    }

    /** 32-byte SHA-256 as stable lowercase hex; null/empty/32-byte are pairwise distinct. */
    private static void hashField(StringBuilder sb, byte[] value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            field(sb, hex(value));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
