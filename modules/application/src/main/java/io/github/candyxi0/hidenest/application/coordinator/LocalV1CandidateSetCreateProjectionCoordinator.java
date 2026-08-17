package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalPublishRequest;
import io.github.candyxi0.hidenest.application.model.CanonicalPublishRequest.RelationSpec;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetProjectionResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetProjectionResult.CandidateProjection;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.memory.domain.CandidateEvidenceMapping;
import io.github.candyxi0.hidenest.memory.domain.CandidateSet;
import io.github.candyxi0.hidenest.memory.domain.CandidateSetMember;
import io.github.candyxi0.hidenest.memory.domain.Decision;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.domain.ProposalRevision;
import io.github.candyxi0.hidenest.memory.domain.ReviewMember;
import io.github.candyxi0.hidenest.memory.domain.ReviewSession;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Local V1 CandidateSet CREATE projection coordinator.
 *
 * <p>It projects an already-frozen Task36A CandidateSet into Local V1 normative memories: only
 * {@code disposition=ACCEPTED && action=CREATE} candidates are published, each through the existing
 * {@link CanonicalPublishCoordinator#publishFirst} (no second Memory/Revision/Policy/Outbox/Receipt
 * writer). Canonical facts are committed first, then the current revision is indexed through
 * {@link LocalV1VectorCoordinator#indexCurrentRevision}; a single vector failure never rolls back or
 * downgrades the already-committed canonical fact and never blocks the remaining candidates.</p>
 *
 * <p>The public entry only receives {@code candidateSetId}. Body, candidates and evidence refs are
 * read from the committed database facts; the caller can never re-submit a driftable candidate set.
 * The whole set is validated read-only before the first write, so any closure defect yields zero new
 * Memory/Revision/Embedding. Accepted REVISE/SUPERSEDE fail closed before any publish and are
 * recorded as {@code ROUTED_TO_POST_LOCAL_V1_GOVERNANCE}, never silently downgraded to CREATE.</p>
 */
public class LocalV1CandidateSetCreateProjectionCoordinator {

    private static final String INDEX_READY = "INDEX_READY";
    private static final String CANONICAL_COMMITTED = "CANONICAL_COMMITTED";
    private static final String NO_ACCEPTED_CANDIDATES = "NO_ACCEPTED_CANDIDATES";
    private static final String REJECTED = "REJECTED";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String COMPLETED = "COMPLETED";
    private static final String ACTIVE = "ACTIVE";
    private static final String EVIDENCED_BY = "EVIDENCED_BY";
    private static final String DECISION_IDEMPOTENCY_PREFIX = "cs-verdict-";
    private static final String CANONICAL_PUBLISH = "CANONICAL_PUBLISH";
    private static final String COMMITTED = "COMMITTED";
    private static final String MEMORY = "MEMORY";
    private static final Set<String> VALID_MEMORY_TYPES =
            Set.of("Event", "Claim", "Quote", "Interpretation", "Calibration", "Principle");

    private final CandidateSetGovernancePort candidateSetPort;
    private final MemoryGovernancePort memoryPort;
    private final MemoryReadPort memoryReadPort;
    private final EvidenceReferencePort evidencePort;
    private final CanonicalPublishCoordinator publish;
    private final LocalV1VectorCoordinator vector;
    private final MemoryVectorStorePort vectorStore;
    private final ModelFingerprint fingerprint;
    private final RuntimeTransactionPort runtimePort;

    public LocalV1CandidateSetCreateProjectionCoordinator(
            CandidateSetGovernancePort candidateSetPort,
            MemoryGovernancePort memoryPort,
            MemoryReadPort memoryReadPort,
            EvidenceReferencePort evidencePort,
            CanonicalPublishCoordinator publish,
            LocalV1VectorCoordinator vector,
            MemoryVectorStorePort vectorStore,
            ModelFingerprint fingerprint,
            RuntimeTransactionPort runtimePort) {
        this.candidateSetPort = Objects.requireNonNull(candidateSetPort, "candidateSetPort");
        this.memoryPort = Objects.requireNonNull(memoryPort, "memoryPort");
        this.memoryReadPort = Objects.requireNonNull(memoryReadPort, "memoryReadPort");
        this.evidencePort = Objects.requireNonNull(evidencePort, "evidencePort");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.vector = Objects.requireNonNull(vector, "vector");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.runtimePort = Objects.requireNonNull(runtimePort, "runtimePort");
    }

    // ── project ───────────────────────────────────────────────────────────

    public LocalV1CandidateSetProjectionResult project(UUID candidateSetId) {
        if (candidateSetId == null) {
            throw failure(LocalV1CandidateSetProjectionException.Code.CANDIDATE_SET_NOT_FOUND);
        }

        Preflight preflight = preflight(candidateSetId);

        List<CandidateProjection> records = new ArrayList<>(preflight.members().size());
        boolean anyAccepted = false;
        boolean anyCanonicalNotIndexed = false;

        for (MemberView view : preflight.members()) {
            CandidateSetMember member = view.member();
            if (!ACCEPTED.equals(member.disposition())) {
                records.add(new CandidateProjection(
                        member.candidateId(), member.ordinal(), member.disposition(), member.action(),
                        null, null, null, REJECTED));
                continue;
            }
            anyAccepted = true;

            CanonicalPublishRequest request = buildPublishRequest(preflight, view);
            try {
                publish.publishFirst(request);
            } catch (RuntimeException exception) {
                throw failure(LocalV1CandidateSetProjectionException.Code.CANONICAL_PUBLISH_FAILED, exception);
            }

            PublishedMemory published = verifyPublishedMemory(view, request);

            String phase;
            if (published.indexed()) {
                phase = INDEX_READY;
            } else {
                try {
                    vector.indexCurrentRevision(member.futureMemoryId());
                    phase = INDEX_READY;
                } catch (RuntimeException exception) {
                    phase = CANONICAL_COMMITTED;
                    anyCanonicalNotIndexed = true;
                }
            }

            records.add(new CandidateProjection(
                    member.candidateId(), member.ordinal(), member.disposition(), member.action(),
                    member.futureMemoryId(), published.revisionId(), published.revisionNo(), phase));
        }

        if (!anyAccepted) {
            return new LocalV1CandidateSetProjectionResult(candidateSetId, NO_ACCEPTED_CANDIDATES, records);
        }
        String overall = anyCanonicalNotIndexed ? CANONICAL_COMMITTED : INDEX_READY;
        return new LocalV1CandidateSetProjectionResult(candidateSetId, overall, records);
    }

    // ── read-only preflight ───────────────────────────────────────────────

    private Preflight preflight(UUID candidateSetId) {
        CandidateSet set = candidateSetPort.findCandidateSetById(candidateSetId);
        if (set == null) {
            throw failure(LocalV1CandidateSetProjectionException.Code.CANDIDATE_SET_NOT_FOUND);
        }
        ReviewSession session = memoryPort.findReviewSessionById(set.reviewSessionId());
        if (session == null || !COMPLETED.equals(session.state())) {
            throw failure(LocalV1CandidateSetProjectionException.Code.REVIEW_SESSION_NOT_COMPLETED);
        }

        List<CandidateSetMember> members = candidateSetPort.findCandidateSetMembers(candidateSetId);
        if (members == null || members.isEmpty()) {
            throw failure(LocalV1CandidateSetProjectionException.Code.MEMBER_CLOSURE_INVALID);
        }

        // ordinal contiguity + candidateId uniqueness + set binding.
        Set<UUID> candidateIds = new HashSet<>();
        long expectedOrdinal = 1;
        for (CandidateSetMember member : members) {
            if (member.candidateId() == null
                    || !candidateSetId.equals(member.candidateSetId())
                    || member.ordinal() != expectedOrdinal++
                    || !candidateIds.add(member.candidateId())) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MEMBER_CLOSURE_INVALID);
            }
        }

        // ReviewSession ↔ member binding via review_member.
        List<ReviewMember> reviewMembers = memoryPort.findReviewMembersBySessionId(set.reviewSessionId());
        Map<UUID, ReviewMember> reviewMemberByRevision = new HashMap<>();
        for (ReviewMember reviewMember : reviewMembers) {
            if (reviewMember.proposalRevisionId() == null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MEMBER_CLOSURE_INVALID);
            }
            reviewMemberByRevision.put(reviewMember.proposalRevisionId(), reviewMember);
        }
        if (reviewMembers.size() != members.size()) {
            throw failure(LocalV1CandidateSetProjectionException.Code.MEMBER_CLOSURE_INVALID);
        }

        // Mappings grouped by candidate, plus anchor existence closure.
        List<CandidateEvidenceMapping> mappings = candidateSetPort.findCandidateEvidenceMappings(candidateSetId);
        Map<UUID, List<CandidateEvidenceMapping>> mappingsByCandidate = new HashMap<>();
        Set<UUID> allAnchorIds = new HashSet<>();
        for (CandidateEvidenceMapping mapping : mappings) {
            if (!candidateSetId.equals(mapping.candidateSetId()) || mapping.anchorId() == null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MAPPING_CLOSURE_INVALID);
            }
            mappingsByCandidate.computeIfAbsent(mapping.candidateId(), k -> new ArrayList<>()).add(mapping);
            allAnchorIds.add(mapping.anchorId());
        }
        for (UUID anchorId : allAnchorIds) {
            if (evidencePort.findSourceAnchorById(anchorId) == null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.ORPHAN_EVIDENCE_ANCHOR);
            }
        }

        Set<UUID> acceptedFutureMemoryIds = new HashSet<>();
        Set<UUID> acceptedProposalRevisionIds = new HashSet<>();
        Set<UUID> acceptedDecisionIds = new HashSet<>();

        List<MemberView> views = new ArrayList<>(members.size());
        for (CandidateSetMember member : members) {
            boolean accepted = ACCEPTED.equals(member.disposition());
            // Accepted REVISE/SUPERSEDE is out of Local V1 scope. Reject before any per-member
            // verification or publish (ROUTED_TO_POST_LOCAL_V1_GOVERNANCE), never downgrade to CREATE.
            if (accepted && !"CREATE".equals(member.action())) {
                throw failure(LocalV1CandidateSetProjectionException.Code.UNSUPPORTED_CANDIDATE_ACTION);
            }

            ReviewMember reviewMember = reviewMemberByRevision.get(member.proposalRevisionId());
            if (reviewMember == null || reviewMember.ordinal() == null || reviewMember.ordinal() != member.ordinal()) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MEMBER_CLOSURE_INVALID);
            }

            ProposalRevision revision = memoryPort.findProposalRevisionById(member.proposalRevisionId());
            if (revision == null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
            }
            verifyProposalRevision(revision, member);

            Decision decision = memoryPort.findDecisionByIdempotencyKey(
                    DECISION_IDEMPOTENCY_PREFIX + candidateSetId + "-" + member.candidateId());
            if (decision == null || !member.decisionId().equals(decision.decisionId())) {
                throw failure(LocalV1CandidateSetProjectionException.Code.DECISION_BINDING_MISMATCH);
            }
            verifyDecision(decision, member, revision, set.reviewSessionId());

            List<UUID> anchorIds = verifyMappings(member, accepted, mappingsByCandidate.get(member.candidateId()));

            if (accepted) {
                if (member.futureMemoryId() == null
                        || !acceptedFutureMemoryIds.add(member.futureMemoryId())
                        || !acceptedProposalRevisionIds.add(member.proposalRevisionId())
                        || !acceptedDecisionIds.add(member.decisionId())) {
                    throw failure(LocalV1CandidateSetProjectionException.Code.DUPLICATE_PROJECTION_IDENTITY);
                }
            }

            views.add(new MemberView(member, revision, anchorIds));
        }
        return new Preflight(candidateSetId, set.reviewSessionId(), views);
    }

    private void verifyProposalRevision(ProposalRevision revision, CandidateSetMember member) {
        if (revision.proposalId() == null
                || revision.revisionNo() == null
                || revision.revisionNo() != 1L
                || !"PUBLISH".equals(revision.actionCode())
                || !member.proposalRevisionId().equals(revision.proposalRevisionId())) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
        }
        if (ACCEPTED.equals(member.disposition())) {
            // R1-01: accepted CREATE must carry a publishable, hash-bound body fact.
            if (revision.bodyText() == null
                    || revision.bodyText().isBlank()
                    || revision.memoryType() == null
                    || !VALID_MEMORY_TYPES.contains(revision.memoryType())
                    || revision.perspectiveActorId() == null
                    || revision.bodyHash() == null
                    || revision.bodyHash().length != 32
                    || !MessageDigest.isEqual(revision.bodyHash(), sha256(revision.bodyText()))) {
                throw failure(LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
            }
        } else if (revision.bodyText() == null) {
            if (revision.bodyHash() != null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
            }
        } else if (revision.bodyHash() == null
                || !MessageDigest.isEqual(revision.bodyHash(), sha256(revision.bodyText()))) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
        }
    }

    private void verifyDecision(
            Decision decision, CandidateSetMember member, ProposalRevision revision, UUID reviewSessionId) {
        if (!member.proposalRevisionId().equals(decision.proposalRevisionId())
                || !reviewSessionId.equals(decision.reviewSessionId())
                || revision.perspectiveActorId() == null
                || !revision.perspectiveActorId().equals(decision.actorId())) {
            throw failure(LocalV1CandidateSetProjectionException.Code.DECISION_BINDING_MISMATCH);
        }
        if (ACCEPTED.equals(member.disposition())) {
            if (!"USER_CONFIRM".equals(decision.decisionKind())
                    || !"MEMORY".equals(decision.targetKind())
                    || !member.futureMemoryId().equals(decision.targetId())
                    || decision.targetRevisionRef() == null
                    || decision.targetRevisionRef() != 1L) {
                throw failure(LocalV1CandidateSetProjectionException.Code.DECISION_BINDING_MISMATCH);
            }
        } else {
            if (!"USER_REJECT".equals(decision.decisionKind())
                    || !"PROPOSAL".equals(decision.targetKind())
                    || !revision.proposalId().equals(decision.targetId())
                    || decision.targetRevisionRef() == null
                    || decision.targetRevisionRef() != 1L) {
                throw failure(LocalV1CandidateSetProjectionException.Code.DECISION_BINDING_MISMATCH);
            }
        }
    }

    private List<UUID> verifyMappings(
            CandidateSetMember member, boolean accepted, List<CandidateEvidenceMapping> candidateMappings) {
        if (candidateMappings == null || candidateMappings.isEmpty()) {
            if (accepted) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MAPPING_CLOSURE_INVALID);
            }
            return List.of();
        }
        if (!accepted) {
            throw failure(LocalV1CandidateSetProjectionException.Code.MAPPING_CLOSURE_INVALID);
        }
        List<CandidateEvidenceMapping> sorted = candidateMappings.stream()
                .sorted(Comparator.comparingLong(CandidateEvidenceMapping::ordinal))
                .toList();
        Set<UUID> seen = new HashSet<>();
        long expected = 1;
        for (CandidateEvidenceMapping mapping : sorted) {
            if (mapping.ordinal() != expected++ || !seen.add(mapping.anchorId())) {
                throw failure(LocalV1CandidateSetProjectionException.Code.MAPPING_CLOSURE_INVALID);
            }
        }
        List<UUID> anchors = new ArrayList<>(sorted.size());
        for (CandidateEvidenceMapping mapping : sorted) {
            anchors.add(mapping.anchorId());
        }
        return anchors;
    }

    // ── per-candidate publish + post-commit verification ──────────────────

    private CanonicalPublishRequest buildPublishRequest(Preflight preflight, MemberView view) {
        CandidateSetMember member = view.member();
        ProposalRevision revision = view.proposalRevision();
        List<RelationSpec> relations = new ArrayList<>(view.anchorIds().size());
        for (UUID anchorId : view.anchorIds()) {
            relations.add(new RelationSpec(EVIDENCED_BY, null, anchorId, revision.perspectiveActorId()));
        }
        byte[] requestHash = LocalV1CandidateSetProjectionCanonicalizer.requestHash(
                preflight.candidateSetId(), member.candidateId(), member.ordinal(),
                member.decisionId(), member.proposalRevisionId(), preflight.reviewSessionId(),
                member.futureMemoryId(), revision.memoryType(), revision.perspectiveActorId(),
                revision.bodyHash(), view.anchorIds());
        byte[] manifestHash = LocalV1CandidateSetProjectionCanonicalizer.manifestHash(
                preflight.candidateSetId(), member.candidateId(), member.ordinal(),
                member.decisionId(), member.proposalRevisionId(), preflight.reviewSessionId(),
                member.futureMemoryId(), revision.memoryType(), revision.perspectiveActorId(),
                revision.bodyHash(), view.anchorIds());
        return new CanonicalPublishRequest(
                LocalV1CandidateSetProjectionCanonicalizer.publishIdempotencyKey(
                        preflight.candidateSetId(), member.candidateId()),
                requestHash,
                Set.of(member.decisionId()),
                member.proposalRevisionId(),
                preflight.reviewSessionId(),
                member.futureMemoryId(),
                revision.memoryType(),
                revision.perspectiveActorId(),
                revision.bodyText(),
                LocalV1CandidateSetProjectionCanonicalizer.policyId(
                        preflight.candidateSetId(), member.candidateId()),
                relations,
                manifestHash);
    }

    private PublishedMemory verifyPublishedMemory(MemberView view, CanonicalPublishRequest request) {
        CandidateSetMember member = view.member();
        ProposalRevision proposal = view.proposalRevision();
        UUID memoryId = member.futureMemoryId();

        MemoryRecord record = memoryReadPort.findMemoryRecordById(memoryId);
        if (record == null
                || !ACTIVE.equals(record.state())
                || record.currentRevisionId() == null
                || !request.policyId().equals(record.policyId())
                || record.currentPolicyRevisionNo() == null
                || record.currentPolicyRevisionNo() != 1L) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        }
        MemoryRevision revision = memoryReadPort.findCurrentRevisionByMemoryId(memoryId);
        if (revision == null
                || !record.currentRevisionId().equals(revision.memoryRevisionId())
                || !memoryId.equals(revision.memoryId())
                || revision.revisionNo() == null
                || revision.revisionNo() != 1L
                || revision.bodyText() == null
                || !proposal.memoryType().equals(revision.memoryType())
                || !proposal.perspectiveActorId().equals(revision.perspectiveActorId())
                || !member.decisionId().equals(revision.createdByDecisionId())
                || !MessageDigest.isEqual(
                        proposal.bodyText().getBytes(StandardCharsets.UTF_8),
                        revision.bodyText().getBytes(StandardCharsets.UTF_8))) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        }

        // EVIDENCED_BY relations must be exactly the expected anchors (no missing, no extra, no other kind).
        Set<UUID> expectedAnchors = new LinkedHashSet<>(view.anchorIds());
        Set<UUID> actualAnchors = new LinkedHashSet<>();
        for (MemoryRelation relation : memoryReadPort.findRelationsByFromRevisionId(revision.memoryRevisionId())) {
            if (!EVIDENCED_BY.equals(relation.relationType()) || relation.toAnchorId() == null) {
                throw failure(LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
            }
            actualAnchors.add(relation.toAnchorId());
        }
        if (!actualAnchors.equals(expectedAnchors)) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        }

        verifyPublishReceipt(request, memoryId, revision.memoryRevisionId());

        boolean indexed = vectorStore.hasEmbedding(
                revision.memoryRevisionId(),
                fingerprint.modelName(),
                fingerprint.ggufSha256(),
                fingerprint.dimension(),
                fingerprint.normalization(),
                sha256(revision.bodyText()));

        return new PublishedMemory(revision.memoryRevisionId(), revision.revisionNo(), indexed);
    }

    private void verifyPublishReceipt(CanonicalPublishRequest request, UUID memoryId, UUID revisionId) {
        IdempotencyReceipt receipt = runtimePort.findReceiptByKey(request.idempotencyKey());
        if (receipt == null
                || !request.idempotencyKey().equals(receipt.idempotencyKey())
                || !CANONICAL_PUBLISH.equals(receipt.operationCode())
                || !COMMITTED.equals(receipt.state())
                || !MEMORY.equals(receipt.resourceKind())
                || !memoryId.equals(receipt.resourceId())
                || receipt.requestHash() == null
                || !MessageDigest.isEqual(receipt.requestHash(), request.requestHash())
                || !isCanonicalPublishManifest(receipt.responseManifest(), revisionId)
                || receipt.createdAt() == null
                || receipt.committedAt() == null) {
            throw failure(LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        }
    }

    /**
     * Strict closed-shape verification of the canonical-publish response manifest. The manifest is
     * stored as {@code jsonb}, so key order and insignificant whitespace may vary; this never relies
     * on any fixed string layout. It accepts exactly one flat JSON object whose key set is exactly
     * {@code {type,status,requestId,resultCategory,retryable}} with the exact types and values:
     * {@code type} is the string {@code urn:pink:response:canonical-publish}, {@code status} is the
     * JSON number 200, {@code requestId} is the canonical UUID string of the actual revision,
     * {@code resultCategory} is the string {@code SUCCEEDED}, and {@code retryable} is the JSON
     * boolean {@code false}. Extra keys, duplicate keys, nested objects/arrays, wrong types, string
     * {@code "200"} / {@code "false"}, or a correct UUID appearing only in an unrelated field all
     * fail closed. No Jackson/JSON dependency is introduced.
     */
    private static boolean isCanonicalPublishManifest(String manifest, UUID revisionId) {
        if (manifest == null) {
            return false;
        }
        ManifestParser parser = new ManifestParser(manifest);
        Map<String, Object> object = parser.parseObject();
        if (object == null || !parser.atEnd()) {
            return false;
        }
        if (object.size() != 5) {
            return false;
        }
        Object type = object.get("type");
        if (!(type instanceof String typeValue) || !"urn:pink:response:canonical-publish".equals(typeValue)) {
            return false;
        }
        Object status = object.get("status");
        if (!(status instanceof Long statusValue) || statusValue != 200L) {
            return false;
        }
        Object requestId = object.get("requestId");
        if (!(requestId instanceof String requestIdValue) || !revisionId.toString().equals(requestIdValue)) {
            return false;
        }
        Object resultCategory = object.get("resultCategory");
        if (!(resultCategory instanceof String categoryValue) || !"SUCCEEDED".equals(categoryValue)) {
            return false;
        }
        Object retryable = object.get("retryable");
        if (!(retryable instanceof Boolean retryableValue) || retryableValue) {
            return false;
        }
        return true;
    }

    /** Minimal fail-closed JSON parser for a single flat object with string/number/boolean values. */
    private static final class ManifestParser {
        private final String source;
        private int index;

        ManifestParser(String source) {
            this.source = source;
        }

        boolean atEnd() {
            skipWhitespace();
            return index >= source.length();
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            if (index >= source.length() || source.charAt(index) != '{') {
                return null;
            }
            index++;
            Map<String, Object> values = new HashMap<>();
            skipWhitespace();
            if (index < source.length() && source.charAt(index) == '}') {
                index++;
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                if (key == null || values.containsKey(key)) {
                    return null; // malformed or duplicate key
                }
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != ':') {
                    return null;
                }
                index++;
                Object value = parseValue();
                if (value == null) {
                    return null;
                }
                values.put(key, value);
                skipWhitespace();
                if (index >= source.length()) {
                    return null;
                }
                char separator = source.charAt(index);
                if (separator == ',') {
                    index++;
                    continue;
                }
                if (separator == '}') {
                    index++;
                    return values;
                }
                return null;
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= source.length()) {
                return null;
            }
            char c = source.charAt(index);
            if (c == '"') {
                return parseString();
            }
            if (c == '{' || c == '[') {
                return null; // no nested object/array in a canonical flat manifest
            }
            if (c == 't') {
                return parseLiteral("true", Boolean.TRUE);
            }
            if (c == 'f') {
                return parseLiteral("false", Boolean.FALSE);
            }
            if (c == 'n') {
                return parseLiteral("null", null);
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            return null;
        }

        private String parseString() {
            if (index >= source.length() || source.charAt(index) != '"') {
                return null;
            }
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == '"') {
                    index++;
                    return builder.toString();
                }
                if (c == '\\' || c < 0x20) {
                    return null; // no escapes/control chars in a canonical flat manifest
                }
                builder.append(c);
                index++;
            }
            return null;
        }

        private Object parseLiteral(String literal, Object value) {
            if (source.startsWith(literal, index)) {
                index += literal.length();
                return value;
            }
            return null;
        }

        private Object parseNumber() {
            int start = index;
            if (index < source.length() && source.charAt(index) == '-') {
                index++;
            }
            boolean digits = false;
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
                index++;
                digits = true;
            }
            if (!digits) {
                return null;
            }
            boolean integer = true;
            if (index < source.length() && source.charAt(index) == '.') {
                integer = false;
                index++;
                if (index >= source.length() || !isDigit(source.charAt(index))) {
                    return null;
                }
                while (index < source.length() && isDigit(source.charAt(index))) {
                    index++;
                }
            }
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                integer = false;
                index++;
                if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    index++;
                }
                if (index >= source.length() || !isDigit(source.charAt(index))) {
                    return null;
                }
                while (index < source.length() && isDigit(source.charAt(index))) {
                    index++;
                }
            }
            String token = source.substring(start, index);
            try {
                if (integer) {
                    return Long.valueOf(token);
                }
                return Double.valueOf(token);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static LocalV1CandidateSetProjectionException failure(LocalV1CandidateSetProjectionException.Code code) {
        return new LocalV1CandidateSetProjectionException(code);
    }

    private static LocalV1CandidateSetProjectionException failure(
            LocalV1CandidateSetProjectionException.Code code, Throwable cause) {
        return new LocalV1CandidateSetProjectionException(code, cause);
    }

    private record MemberView(
            CandidateSetMember member,
            ProposalRevision proposalRevision,
            List<UUID> anchorIds) {}

    private record Preflight(UUID candidateSetId, UUID reviewSessionId, List<MemberView> members) {}

    private record PublishedMemory(UUID revisionId, Long revisionNo, boolean indexed) {}
}
