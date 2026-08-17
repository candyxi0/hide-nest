package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Single frozen canonical hasher for the Local V1 CandidateSet content binding.
 *
 * <p>Production code and tests must both call this class; no second hand-written algorithm is
 * allowed. Encoding is unambiguous length-prefixed: {@code UTF8(byteLength) + ':' + UTF8(value)};
 * null is {@code -1:}; UUIDs are lowercase-hyphenated text; integers are decimal text.</p>
 */
public final class LocalV1CandidateSetCanonicalizer {

    private LocalV1CandidateSetCanonicalizer() {}

    /**
     * Deterministic review session identity for a CandidateSet.
     *
     * <p>This is the single authoritative derivation; every caller (batch coordinator, HTTP
     * facade, MCP, tests) must use this method. No second copy of the derivation is allowed.</p>
     */
    public static UUID reviewSessionId(UUID candidateSetId) {
        return UUID.nameUUIDFromBytes(
                ("candidate-set:review:" + candidateSetId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Final confirmation hash. Binds setVersion plus every candidate's final fields ordered by
     * ordinal — identity, disposition, action, origin/author attribution, target and evidence
     * refs — so a tampered candidate, an old setVersion, a reordered member or a doctored
     * evidence reference always changes the hash. It deliberately excludes {@code hideReason}
     * (review-only material) and the confirmation hash itself.
     */
    public static byte[] confirmationHash(LocalV1CandidateSetRequest request) {
        StringBuilder sb = new StringBuilder();
        number(sb, request.setVersion());
        List<LocalV1CandidateSetRequest.Candidate> sorted = request.candidates().stream()
                .sorted(Comparator.comparingLong(LocalV1CandidateSetRequest.Candidate::ordinal))
                .toList();
        number(sb, sorted.size());
        for (var c : sorted) {
            field(sb, c.candidateId().toString());
            number(sb, c.ordinal());
            field(sb, c.disposition());
            field(sb, c.action());
            field(sb, c.originKind());
            field(sb, c.finalAuthorKind());
            field(sb, c.memoryText());
            field(sb, c.memoryType());
            uuidField(sb, c.perspectiveActorId());
            number(sb, c.evidenceAnchorIds().size());
            for (UUID anchorId : c.evidenceAnchorIds()) {
                field(sb, anchorId.toString());
            }
            uuidField(sb, c.targetMemoryId());
            uuidField(sb, c.expectedMemoryRevisionId());
            nullableNumber(sb, c.expectedRevisionNo());
            nullableNumber(sb, c.expectedPolicyRevisionNo());
        }
        // Evidence speaker roles bind the confirmation to the "who spoke" dimension, decoupled
        // from perspective. Mirrors the TypeScript confirmationHash addition exactly.
        number(sb, request.evidencePool().messages().size());
        for (var m : request.evidencePool().messages()) {
            field(sb, m.sourceUnitId().toString());
            field(sb, m.speakerRole());
        }
        return sha256(sb.toString());
    }

    /**
     * Full request hash used for idempotency and conflict detection. Binds the whole request —
     * including {@code hideReason} and the evidence pool — but not the derived confirmation hash.
     */
    public static byte[] requestHash(LocalV1CandidateSetRequest request) {
        StringBuilder sb = new StringBuilder();
        field(sb, request.candidateSetId().toString());
        field(sb, request.idempotencyKey());
        field(sb, request.threadId().toString());
        field(sb, request.scopeRef());
        number(sb, request.setVersion());
        field(sb, request.finalConfirmation().decision());
        number(sb, request.finalConfirmation().confirmedSetVersion());

        // evidence pool: messages then anchors (frozen order)
        number(sb, request.evidencePool().messages().size());
        for (var m : request.evidencePool().messages()) {
            field(sb, m.sourceUnitId().toString());
            field(sb, m.actorId().toString());
            field(sb, m.speakerRole());
            number(sb, m.ordinal());
            field(sb, m.externalUnitRef());
            field(sb, m.occurredAt().toInstant().toString());
            field(sb, m.bodyText());
            field(sb, hex(m.bodyHash()));
        }
        number(sb, request.evidencePool().anchors().size());
        for (var a : request.evidencePool().anchors()) {
            field(sb, a.anchorId().toString());
            number(sb, a.units().size());
            for (var u : a.units()) {
                field(sb, u.sourceUnitId().toString());
                nullableNumber(sb, u.fromOffset());
                nullableNumber(sb, u.toOffset());
                number(sb, u.ordinal());
            }
        }

        // candidates in frozen order (includes hideReason)
        number(sb, request.candidates().size());
        for (var c : request.candidates()) {
            field(sb, c.candidateId().toString());
            number(sb, c.ordinal());
            field(sb, c.disposition());
            field(sb, c.action());
            field(sb, c.originKind());
            field(sb, c.finalAuthorKind());
            field(sb, c.memoryText());
            field(sb, c.memoryType());
            uuidField(sb, c.perspectiveActorId());
            number(sb, c.evidenceAnchorIds().size());
            for (UUID anchorId : c.evidenceAnchorIds()) {
                field(sb, anchorId.toString());
            }
            uuidField(sb, c.targetMemoryId());
            uuidField(sb, c.expectedMemoryRevisionId());
            nullableNumber(sb, c.expectedRevisionNo());
            nullableNumber(sb, c.expectedPolicyRevisionNo());
            field(sb, c.hideReason());
        }
        return sha256(sb.toString());
    }

    // ── length-prefixed encoding ──────────────────────────────────────────

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

    private static void nullableNumber(StringBuilder sb, Long value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            number(sb, value);
        }
    }

    private static void uuidField(StringBuilder sb, UUID value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            field(sb, value.toString());
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
