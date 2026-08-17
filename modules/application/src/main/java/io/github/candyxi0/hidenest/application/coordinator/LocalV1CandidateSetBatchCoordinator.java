package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorSpec;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.Candidate;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetResult.CandidateOutcome;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import io.github.candyxi0.hidenest.evidence.domain.Source;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchor;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchorUnit;
import io.github.candyxi0.hidenest.evidence.domain.SourcePayload;
import io.github.candyxi0.hidenest.evidence.domain.SourceUnit;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicy;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicyRevision;
import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.CandidateEvidenceMapping;
import io.github.candyxi0.hidenest.memory.domain.CandidateSet;
import io.github.candyxi0.hidenest.memory.domain.CandidateSetMember;
import io.github.candyxi0.hidenest.memory.domain.ChangeEvent;
import io.github.candyxi0.hidenest.memory.domain.Decision;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.domain.Proposal;
import io.github.candyxi0.hidenest.memory.domain.ProposalRevision;
import io.github.candyxi0.hidenest.memory.domain.ReviewMember;
import io.github.candyxi0.hidenest.memory.domain.ReviewSession;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local V1 multi-candidate closeout write coordinator.
 *
 * <p>Atomically persists an already-confirmed CandidateSet: one ReviewSession, 1..8 candidates,
 * one final verdict Decision (USER_CONFIRM / USER_REJECT) per member, one governed
 * ChangeEvent/Outbox per verdict, the shared evidence union (deduplicated Source/SourceUnit/
 * SourcePayload/SourceAnchor), the CandidateSet governance facts and the batch receipt — all in a
 * single transaction. It never publishes Memory/MemoryRevision (that is Task36B), never touches
 * HTTP/MCP/Embedding, and never calls the single-candidate {@code LocalV1S1WindowCloseCoordinator}.</p>
 */
public class LocalV1CandidateSetBatchCoordinator {

    private static final String PLATFORM = "LOCAL_V1";
    private static final String OPERATION_CODE = "LOCAL_V1_CANDIDATE_SET";
    private static final Set<String> VALID_MEMORY_TYPES =
            Set.of("Event", "Claim", "Quote", "Interpretation", "Calibration", "Principle");
    private static final Set<String> VALID_DISPOSITIONS = Set.of("ACCEPTED", "REJECTED");
    private static final Set<String> VALID_ACTIONS = Set.of("CREATE", "REVISE", "SUPERSEDE");
    private static final Set<String> VALID_ORIGINS = Set.of("HIDE_PROPOSED", "USER_EDITED", "USER_ADDED");
    private static final Set<String> VALID_AUTHORS = Set.of("HIDE", "USER");
    private static final Set<String> VALID_SPEAKER_ROLES = Set.of("XIAOLIN", "HIDE");

    private final EvidenceReferencePort evidencePort;
    private final MemoryGovernancePort memoryPort;
    private final RuntimeTransactionPort runtimePort;
    private final CandidateSetGovernancePort candidateSetPort;
    private final TransactionExecutor tx;
    private final PayloadStore payloadStore;
    private final Clock clock;

    public LocalV1CandidateSetBatchCoordinator(
            EvidenceReferencePort evidencePort,
            MemoryGovernancePort memoryPort,
            RuntimeTransactionPort runtimePort,
            CandidateSetGovernancePort candidateSetPort,
            TransactionExecutor tx,
            PayloadStore payloadStore,
            Clock clock) {
        this.evidencePort = evidencePort;
        this.memoryPort = memoryPort;
        this.runtimePort = runtimePort;
        this.candidateSetPort = candidateSetPort;
        this.tx = tx;
        this.payloadStore = payloadStore;
        this.clock = clock;
    }

    // ── submit ────────────────────────────────────────────────────────────

    public LocalV1CandidateSetResult submit(LocalV1CandidateSetRequest request) {
        validateStructure(request);
        byte[] expectedRequestHash = LocalV1CandidateSetCanonicalizer.requestHash(request);
        if (!MessageDigest.isEqual(expectedRequestHash, request.requestHash())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }
        byte[] expectedConfirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(request);
        if (!MessageDigest.isEqual(
                expectedConfirmationHash, request.finalConfirmation().confirmationHash())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }

        if (request.candidates().isEmpty()) {
            return submitNoCandidates(request);
        }

        // Payload file lifecycle lives OUTSIDE the transaction executor so that both in-callback
        // exceptions and transaction-commit (deferred constraint) exceptions reach the same
        // compensation path. Only files created this round (created()==true) are deleted.
        List<PayloadPutResult> createdPayloads = new ArrayList<>();
        try {
            return tx.executeInTransaction(() -> {
                runtimePort.lockIdempotencyKey(request.idempotencyKey());
                IdempotencyReceipt existing = runtimePort.findReceiptByKey(request.idempotencyKey());
                if (existing != null) {
                    if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                        return replayVerify(request, existing);
                    }
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.IDEMPOTENCY_KEY_REUSED);
                }
                return writeBatch(request, createdPayloads);
            });
        } catch (RuntimeException e) {
            compensateCreatedPayloads(createdPayloads, e);
            if (e instanceof LocalV1CandidateSetException local) {
                throw local;
            }
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.INTERNAL_FAILURE, e);
        }
    }

    private LocalV1CandidateSetResult submitNoCandidates(LocalV1CandidateSetRequest request) {
        return tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(request.idempotencyKey());
            IdempotencyReceipt existing = runtimePort.findReceiptByKey(request.idempotencyKey());
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                    return LocalV1CandidateSetResult.noCandidates(request.candidateSetId());
                }
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.IDEMPOTENCY_KEY_REUSED);
            }
            runtimePort.commitReceipt(
                    request.idempotencyKey(),
                    OPERATION_CODE,
                    request.requestHash(),
                    null,
                    "CANDIDATE_SET",
                    noCandidatesManifest(request.candidateSetId()));
            return LocalV1CandidateSetResult.noCandidates(request.candidateSetId());
        });
    }

    // ── atomic batch write ────────────────────────────────────────────────

    private LocalV1CandidateSetResult writeBatch(
            LocalV1CandidateSetRequest request, List<PayloadPutResult> createdPayloads) {
        UUID candidateSetId = request.candidateSetId();
        OffsetDateTime now = OffsetDateTime.now(clock);

        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        UUID sourceId = deterministicId("candidate-set:source", candidateSetId);
        UUID sourcePolicyId = deterministicId("candidate-set:policy", candidateSetId);
        UUID hideSystemActorId = deterministicId("candidate-set:hide-actor", candidateSetId);
        UUID hideSelectDecisionId = deterministicId("candidate-set:hide-select", candidateSetId);
        UUID policyCeId = deterministicId("candidate-set:policy-ce", candidateSetId);

        Map<UUID, AnchorSpec> anchorById = indexAnchorsById(request);
        Map<UUID, EvidenceMessage> messageById = indexMessagesById(request);

        // Lock + hard-validate REVISE/SUPERSEDE targets before writing any Decision.
        for (Candidate candidate : request.candidates()) {
            validateActionBinding(candidate);
        }

        // R1-04: compute the accepted evidence union BEFORE any Source/Policy/HIDE_SELECT/actor
        // write, so rejected-only evidence and all-rejected sets persist the minimum.
        List<Candidate> accepted = request.candidates().stream()
                .filter(c -> "ACCEPTED".equals(c.disposition()))
                .toList();
        boolean hasAccepted = !accepted.isEmpty();
        List<AnchorSpec> acceptedAnchors = acceptedAnchorSpecs(accepted, anchorById);

        Map<UUID, UUID> canonicalAnchorByCallerId = new HashMap<>();
        Map<String, UUID> canonicalByBoundary = new LinkedHashMap<>();
        Map<String, AnchorSpec> specByBoundary = new HashMap<>();
        for (AnchorSpec spec : acceptedAnchors) {
            String key = boundaryKey(spec);
            UUID canonical = canonicalByBoundary.computeIfAbsent(
                    key, k -> deterministicId("candidate-set:anchor", candidateSetId, k));
            canonicalAnchorByCallerId.put(spec.anchorId(), canonical);
            specByBoundary.putIfAbsent(key, spec);
        }

        LinkedHashSet<UUID> sourceUnitIds = new LinkedHashSet<>();
        Set<UUID> acceptedEvidenceActors = new HashSet<>();
        for (AnchorSpec spec : acceptedAnchors) {
            for (AnchorUnit unit : spec.units()) {
                sourceUnitIds.add(unit.sourceUnitId());
                EvidenceMessage message = messageById.get(unit.sourceUnitId());
                if (message != null) {
                    acceptedEvidenceActors.add(message.actorId());
                }
            }
        }

        Set<UUID> perspectiveActors = new HashSet<>();
        for (Candidate c : request.candidates()) {
            perspectiveActors.add(c.perspectiveActorId());
        }

        // actorId -> speakerRole from the evidence pool; fail closed on missing/conflicting role.
        Map<UUID, String> actorRoleById = new HashMap<>();
        for (EvidenceMessage message : request.evidencePool().messages()) {
            String role = message.speakerRole();
            if (role == null || !VALID_SPEAKER_ROLES.contains(role)) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            String prior = actorRoleById.putIfAbsent(message.actorId(), role);
            if (prior != null && !prior.equals(role)) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
        }

        // 1. Actor refs: perspective actors (Decision identity) + accepted evidence actors only.
        if (hasAccepted) {
            ensureActorRef(
                    new ActorRef(hideSystemActorId, "SYNTHETIC", "cs-hide-" + candidateSetId, "hide", now));
        }
        Set<UUID> allActors = new LinkedHashSet<>();
        allActors.addAll(acceptedEvidenceActors);
        allActors.addAll(perspectiveActors);
        for (UUID actorId : allActors) {
            String role = actorRoleById.get(actorId);
            if (role == null) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            ensureActorRef(new ActorRef(
                    actorId,
                    "SYNTHETIC",
                    "cs-a-" + actorId,
                    roleToLabel(role),
                    now));
        }

        // 2..4. Evidence chain (only when at least one accepted candidate references evidence).
        if (hasAccepted) {
            memoryPort.insertDecision(new Decision(
                    hideSelectDecisionId,
                    "HIDE_SELECT",
                    hideSystemActorId,
                    "USER",
                    null,
                    null,
                    "ACCESS_POLICY",
                    sourcePolicyId,
                    1L,
                    "hide-select",
                    "cs-hs-" + candidateSetId,
                    now));
            memoryPort.insertAccessPolicy(new AccessPolicy(sourcePolicyId, "SOURCE", sourceId, 1L, now));
            memoryPort.insertAccessPolicyRevision(new AccessPolicyRevision(
                    sourcePolicyId, 1L, true, true, false, false, false, hideSelectDecisionId, now));
            memoryPort.insertChangeEvent(new ChangeEvent(
                    policyCeId,
                    null,
                    "memory.policy-changed.v1",
                    hideSystemActorId,
                    "ACCESS_POLICY",
                    sourcePolicyId,
                    1L,
                    hideSelectDecisionId,
                    now,
                    null));
            byte[] emptyHash = new byte[32];
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    deterministicId("candidate-set:policy-ob", candidateSetId),
                    "cs-ob-policy-" + candidateSetId,
                    null,
                    "GOVERNED",
                    "memory.policy-changed.v1",
                    "ACCESS_POLICY",
                    sourcePolicyId,
                    1L,
                    "pink.event.v1",
                    "POLICY_SYNC",
                    1L,
                    emptyHash,
                    outboxManifest(sourcePolicyId, 1L, "POLICY_SYNC", emptyHash),
                    policyCeId,
                    "READY",
                    now,
                    null,
                    null,
                    (short) 0,
                    (short) 8,
                    null,
                    now,
                    null));

            evidencePort.insertSource(new Source(
                    sourceId,
                    "SYNTHETIC_CONVERSATION",
                    PLATFORM,
                    "candidate-set:" + candidateSetId,
                    false,
                    false,
                    sourcePolicyId,
                    now,
                    now));

            for (UUID sourceUnitId : sourceUnitIds) {
                EvidenceMessage message = messageById.get(sourceUnitId);
                if (message == null) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
                evidencePort.insertSourceUnit(new SourceUnit(
                        sourceUnitId,
                        sourceId,
                        message.externalUnitRef(),
                        "v1",
                        message.ordinal(),
                        message.actorId(),
                        message.occurredAt(),
                        now));
                UUID payloadId = deterministicId("candidate-set:payload", candidateSetId, sourceUnitId);
                byte[] bodyBytes = message.bodyText().getBytes(StandardCharsets.UTF_8);
                byte[] bodyHash = message.bodyHash();
                PayloadPutResult putResult =
                        payloadStore.put(payloadId, "text/plain; charset=UTF-8", bodyBytes, bodyHash);
                createdPayloads.add(putResult);
                evidencePort.insertSourcePayload(new SourcePayload(
                        payloadId,
                        sourceUnitId,
                        "TEXT",
                        putResult.storeAdapter(),
                        putResult.objectRef(),
                        null,
                        "text/plain; charset=UTF-8",
                        putResult.sizeBytes(),
                        putResult.contentHash(),
                        sourcePolicyId,
                        1L,
                        "MINIMUM_EVIDENCE",
                        null,
                        now));
            }

            for (String boundary : canonicalByBoundary.keySet()) {
                AnchorSpec spec = specByBoundary.get(boundary);
                UUID canonicalAnchorId = canonicalByBoundary.get(boundary);
                evidencePort.insertSourceAnchor(new SourceAnchor(canonicalAnchorId, sourceId, "MESSAGE_SEGMENT", now));
                List<SourceAnchorUnit> anchorUnits = new ArrayList<>();
                for (AnchorUnit unit : spec.units()) {
                    anchorUnits.add(new SourceAnchorUnit(
                            canonicalAnchorId,
                            unit.sourceUnitId(),
                            unit.fromOffset(),
                            unit.toOffset(),
                            unit.ordinal()));
                }
                if (!anchorUnits.isEmpty()) {
                    evidencePort.insertSourceAnchorUnits(anchorUnits);
                }
            }
        }

        // 5. ReviewSession (OPEN) + members + verdict decisions + governed outbox.
        memoryPort.insertReviewSession(new ReviewSession(
                reviewSessionId, "OPEN", "cs-review-" + candidateSetId, request.requestHash(), now, null));

        for (Candidate candidate : request.candidates()) {
            writeCandidate(candidate, candidateSetId, reviewSessionId, request, now);
        }

        // 6. CandidateSet governance facts.
        candidateSetPort.insertCandidateSet(new CandidateSet(
                candidateSetId,
                reviewSessionId,
                request.threadId(),
                request.scopeRef(),
                request.setVersion(),
                request.finalConfirmation().confirmationHash(),
                request.requestHash(),
                now,
                now));

        for (Candidate candidate : request.candidates()) {
            candidateSetPort.insertCandidateSetMember(new CandidateSetMember(
                    candidateSetId,
                    candidate.candidateId(),
                    candidate.ordinal(),
                    proposalRevisionId(candidateSetId, candidate.candidateId()),
                    decisionId(candidateSetId, candidate.candidateId()),
                    candidate.disposition(),
                    candidate.action(),
                    candidate.originKind(),
                    candidate.finalAuthorKind(),
                    futureMemoryId(candidateSetId, candidate),
                    candidate.targetMemoryId(),
                    candidate.expectedMemoryRevisionId(),
                    candidate.expectedPolicyRevisionNo()));
        }

        List<CandidateEvidenceMapping> mappings = new ArrayList<>();
        for (Candidate candidate : accepted) {
            LinkedHashSet<UUID> anchors = new LinkedHashSet<>();
            for (UUID callerAnchorId : candidate.evidenceAnchorIds()) {
                anchors.add(canonicalAnchorByCallerId.get(callerAnchorId));
            }
            long ordinal = 1;
            for (UUID canonicalAnchorId : anchors) {
                mappings.add(new CandidateEvidenceMapping(
                        candidateSetId, candidate.candidateId(), ordinal++, canonicalAnchorId));
            }
        }
        candidateSetPort.insertCandidateEvidenceMappings(mappings);

        // 7. Complete the review session.
        boolean transitioned = memoryPort.transitionReviewSessionState(reviewSessionId, "OPEN", "COMPLETED", now);
        if (!transitioned) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REVIEW_SESSION_NOT_OPEN);
        }

        // 8. Batch receipt.
        runtimePort.commitReceipt(
                request.idempotencyKey(),
                OPERATION_CODE,
                request.requestHash(),
                candidateSetId,
                "CANDIDATE_SET",
                receiptManifest(candidateSetId));

        List<CandidateOutcome> outcomes = new ArrayList<>();
        for (Candidate candidate : request.candidates()) {
            outcomes.add(new CandidateOutcome(
                    candidate.candidateId(),
                    candidate.ordinal(),
                    candidate.disposition(),
                    candidate.action(),
                    futureMemoryId(candidateSetId, candidate)));
        }
        return new LocalV1CandidateSetResult(candidateSetId, reviewSessionId, "CANONICAL_COMMITTED", outcomes);
    }

    private void ensureActorRef(ActorRef expected) {
        ActorRef persisted = memoryPort.insertActorRefIfAbsent(expected);
        if (persisted == null
                || !expected.actorId().equals(persisted.actorId())
                || !expected.actorKind().equals(persisted.actorKind())
                || !expected.stableRef().equals(persisted.stableRef())
                || !expected.displayLabel().equals(persisted.displayLabel())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }
    }

    private static String roleToLabel(String role) {
        return switch (role) {
            case "XIAOLIN" -> "小林";
            case "HIDE" -> "hide";
            default -> throw new LocalV1CandidateSetException(
                    LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        };
    }

    private void writeCandidate(
            Candidate candidate,
            UUID candidateSetId,
            UUID reviewSessionId,
            LocalV1CandidateSetRequest request,
            OffsetDateTime now) {
        UUID candidateId = candidate.candidateId();
        UUID proposalId = deterministicId("candidate-set:proposal", candidateSetId, candidateId);
        UUID proposalRevisionId = proposalRevisionId(candidateSetId, candidateId);
        UUID decisionId = decisionId(candidateSetId, candidateId);
        UUID changeEventId = deterministicId("candidate-set:ce", candidateSetId, candidateId);

        UUID targetMemoryId = candidate.targetMemoryId();
        String action = candidate.action();

        memoryPort.insertProposal(new Proposal(proposalId, action, targetMemoryId, now));
        memoryPort.insertProposalRevision(new ProposalRevision(
                proposalRevisionId,
                proposalId,
                1L,
                "PUBLISH",
                candidate.memoryText(),
                candidate.memoryType(),
                candidate.perspectiveActorId(),
                candidate.expectedMemoryRevisionId(),
                candidate.expectedPolicyRevisionNo(),
                candidate.memoryText() == null ? null : sha256(candidate.memoryText()),
                now));
        memoryPort.insertReviewMember(new ReviewMember(reviewSessionId, proposalRevisionId, candidate.ordinal()));

        boolean accepted = "ACCEPTED".equals(candidate.disposition());
        String decisionKind = accepted ? "USER_CONFIRM" : "USER_REJECT";
        UUID decisionTargetId;
        if (!accepted) {
            decisionTargetId = proposalId; // rejected → Proposal
        } else if ("REVISE".equals(action)) {
            decisionTargetId = targetMemoryId; // REVISE → existing memory
        } else {
            decisionTargetId = futureMemoryId(candidateSetId, candidate); // CREATE/SUPERSEDE → new memory
        }
        long targetRevisionRef = accepted && "REVISE".equals(action) ? candidate.expectedRevisionNo() + 1 : 1L;

        memoryPort.insertDecision(new Decision(
                decisionId,
                decisionKind,
                candidate.perspectiveActorId(),
                "HUMAN",
                proposalRevisionId,
                reviewSessionId,
                accepted ? "MEMORY" : "PROPOSAL",
                decisionTargetId,
                targetRevisionRef,
                "candidate-set",
                "cs-verdict-" + candidateSetId + "-" + candidateId,
                now));

        memoryPort.insertChangeEvent(new ChangeEvent(
                changeEventId,
                null,
                "review.decisions-committed.v1",
                candidate.perspectiveActorId(),
                "REVIEW_SESSION",
                reviewSessionId,
                targetRevisionRef,
                decisionId,
                now,
                null));

        runtimePort.insertGovernedOutbox(new OutboxEvent(
                deterministicId("candidate-set:ob", candidateSetId, candidateId),
                "cs-ob-" + candidateSetId + "-" + candidateId,
                null,
                "GOVERNED",
                "review.decisions-committed.v1",
                "REVIEW_SESSION",
                reviewSessionId,
                targetRevisionRef,
                "pink.event.v1",
                "REVIEW_SYNC",
                1L,
                request.finalConfirmation().confirmationHash(),
                outboxManifest(
                        reviewSessionId,
                        targetRevisionRef,
                        "REVIEW_SYNC",
                        request.finalConfirmation().confirmationHash()),
                changeEventId,
                "READY",
                now,
                null,
                null,
                (short) 0,
                (short) 8,
                null,
                now,
                null));
    }

    // ── hard validation ───────────────────────────────────────────────────

    private void validateStructure(LocalV1CandidateSetRequest request) {
        if (request == null
                || request.candidateSetId() == null
                || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.requestHash() == null
                || request.requestHash().length != 32
                || request.threadId() == null
                || request.setVersion() < 1
                || request.finalConfirmation() == null
                || !"CONFIRM_SET".equals(request.finalConfirmation().decision())
                || request.finalConfirmation().confirmedSetVersion() != request.setVersion()
                || request.finalConfirmation().confirmationHash() == null
                || request.finalConfirmation().confirmationHash().length != 32
                || request.evidencePool() == null
                || request.evidencePool().messages() == null
                || request.evidencePool().anchors() == null
                || request.candidates() == null
                || request.candidates().size() > 8) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }

        // R1-05/R2-01: closed input — duplicate message ID, bodyHash mismatch, non-empty closed
        // AnchorSpec units, per-anchor ordinal/sourceUnitId/offset closure, non-existent message.
        Map<UUID, EvidenceMessage> messageById = new HashMap<>();
        Map<UUID, String> actorRoles = new HashMap<>();
        Set<UUID> messageIds = new HashSet<>();
        for (EvidenceMessage message : request.evidencePool().messages()) {
            if (message.sourceUnitId() == null
                    || message.actorId() == null
                    || !VALID_SPEAKER_ROLES.contains(message.speakerRole())
                    || !messageIds.add(message.sourceUnitId())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            String priorRole = actorRoles.putIfAbsent(message.actorId(), message.speakerRole());
            if (priorRole != null && !priorRole.equals(message.speakerRole())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            if (message.bodyText() == null
                    || message.bodyHash() == null
                    || !MessageDigest.isEqual(sha256(message.bodyText()), message.bodyHash())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            messageById.put(message.sourceUnitId(), message);
        }
        Map<UUID, Set<UUID>> anchorActors = new HashMap<>();
        Set<UUID> anchorIds = new HashSet<>();
        for (AnchorSpec anchor : request.evidencePool().anchors()) {
            if (anchor.anchorId() == null || !anchorIds.add(anchor.anchorId())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            if (anchor.units() == null || anchor.units().isEmpty()) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            Set<UUID> anchorUnitIds = new HashSet<>();
            Set<UUID> actors = new HashSet<>();
            long expectedUnitOrdinal = 1;
            for (AnchorUnit unit : anchor.units()) {
                if (unit == null
                        || unit.sourceUnitId() == null
                        || !messageIds.contains(unit.sourceUnitId())
                        || !anchorUnitIds.add(unit.sourceUnitId())
                        || unit.ordinal() != expectedUnitOrdinal++) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
                boolean fromNull = unit.fromOffset() == null;
                boolean toNull = unit.toOffset() == null;
                if (fromNull != toNull) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
                if (!fromNull) {
                    int bodyLen = messageById.get(unit.sourceUnitId()).bodyText().length();
                    if (unit.fromOffset() < 0 || unit.toOffset() <= unit.fromOffset() || unit.toOffset() > bodyLen) {
                        throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                    }
                }
                actors.add(messageById.get(unit.sourceUnitId()).actorId());
            }
            anchorActors.put(anchor.anchorId(), actors);
        }

        Set<UUID> candidateIds = new HashSet<>();
        long expectedOrdinal = 1;
        for (Candidate candidate : request.candidates()) {
            if (candidate.candidateId() == null
                    || !candidateIds.add(candidate.candidateId())
                    || candidate.ordinal() != expectedOrdinal++
                    || !VALID_DISPOSITIONS.contains(candidate.disposition())
                    || !VALID_ACTIONS.contains(candidate.action())
                    || !VALID_ORIGINS.contains(candidate.originKind())
                    || !VALID_AUTHORS.contains(candidate.finalAuthorKind())
                    || candidate.perspectiveActorId() == null
                    || candidate.evidenceAnchorIds() == null) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            // Accepted candidates bind perspective to their own evidence below. Rejected
            // candidates intentionally carry no evidence mapping, but their decision actor must
            // still have an explicit role somewhere in the frozen request evidence pool.
            if (!actorRoles.containsKey(candidate.perspectiveActorId())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            boolean accepted = "ACCEPTED".equals(candidate.disposition());
            if (accepted) {
                if (candidate.memoryText() == null
                        || candidate.memoryText().isBlank()
                        || candidate.memoryType() == null
                        || !VALID_MEMORY_TYPES.contains(candidate.memoryType())
                        || candidate.evidenceAnchorIds().isEmpty()) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            } else {
                if (!candidate.evidenceAnchorIds().isEmpty()) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            }
            // duplicate evidenceAnchorId within a candidate, and every anchor must exist in the pool
            Set<UUID> seenAnchors = new HashSet<>();
            for (UUID anchorId : candidate.evidenceAnchorIds()) {
                if (anchorId == null || !seenAnchors.add(anchorId) || !anchorIds.contains(anchorId)) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            }
            // R2-02: accepted candidate's perspective actor must appear in THIS candidate's own
            // referenced evidence actors (not merely the pool or another candidate's evidence).
            if (accepted) {
                Set<UUID> candidateEvidenceActors = new HashSet<>();
                for (UUID anchorId : candidate.evidenceAnchorIds()) {
                    candidateEvidenceActors.addAll(anchorActors.get(anchorId));
                }
                if (!candidateEvidenceActors.contains(candidate.perspectiveActorId())) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            }
            if ("USER_EDITED".equals(candidate.originKind()) || "USER_ADDED".equals(candidate.originKind())) {
                if (!"USER".equals(candidate.finalAuthorKind())) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            }
            boolean reviseOrSupersede = "REVISE".equals(candidate.action()) || "SUPERSEDE".equals(candidate.action());
            if ("CREATE".equals(candidate.action())) {
                if (candidate.targetMemoryId() != null
                        || candidate.expectedMemoryRevisionId() != null
                        || candidate.expectedRevisionNo() != null
                        || candidate.expectedPolicyRevisionNo() != null) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            } else if (reviseOrSupersede) {
                if (candidate.targetMemoryId() == null
                        || candidate.expectedMemoryRevisionId() == null
                        || candidate.expectedRevisionNo() == null
                        || candidate.expectedPolicyRevisionNo() == null) {
                    throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
                }
            }
        }
    }

    private void validateActionBinding(Candidate candidate) {
        if (!"REVISE".equals(candidate.action()) && !"SUPERSEDE".equals(candidate.action())) {
            return;
        }
        MemoryRecord record = memoryPort.lockMemoryRecordForWrite(candidate.targetMemoryId());
        if (record == null || !"ACTIVE".equals(record.state())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.EXPECTED_REVISION_STALE);
        }
        if (!candidate.expectedMemoryRevisionId().equals(record.currentRevisionId())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.EXPECTED_REVISION_STALE);
        }
        if (!candidate.expectedPolicyRevisionNo().equals(record.currentPolicyRevisionNo())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.POLICY_REVISION_STALE);
        }
        MemoryRevision revision = memoryPort.lockMemoryRevisionForWrite(candidate.targetMemoryId());
        if (revision == null
                || !candidate.expectedMemoryRevisionId().equals(revision.memoryRevisionId())
                || !candidate.expectedRevisionNo().equals(revision.revisionNo())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.EXPECTED_REVISION_STALE);
        }
    }

    // ── replay ────────────────────────────────────────────────────────────

    private LocalV1CandidateSetResult replayVerify(LocalV1CandidateSetRequest request, IdempotencyReceipt receipt) {
        UUID candidateSetId = request.candidateSetId();
        if (!OPERATION_CODE.equals(receipt.operationCode())
                || !"CANDIDATE_SET".equals(receipt.resourceKind())
                || !candidateSetId.equals(receipt.resourceId())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
        }

        CandidateSet set = candidateSetPort.findCandidateSetById(candidateSetId);
        if (set == null
                || set.setVersion() != request.setVersion()
                || !Arrays.equals(
                        set.confirmationHash(), request.finalConfirmation().confirmationHash())
                || !Arrays.equals(set.requestHash(), request.requestHash())
                || !request.threadId().equals(set.threadId())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
        }

        ReviewSession session = memoryPort.findReviewSessionById(set.reviewSessionId());
        if (session == null || !"COMPLETED".equals(session.state())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
        }

        List<CandidateSetMember> members = candidateSetPort.findCandidateSetMembers(candidateSetId);
        if (members.size() != request.candidates().size()) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
        }
        Map<UUID, Candidate> candidateById = indexCandidatesById(request);
        for (CandidateSetMember member : members) {
            Candidate expected = candidateById.get(member.candidateId());
            if (expected == null
                    || member.ordinal() != expected.ordinal()
                    || !member.disposition().equals(expected.disposition())
                    || !member.action().equals(expected.action())
                    || !member.originKind().equals(expected.originKind())
                    || !member.finalAuthorKind().equals(expected.finalAuthorKind())
                    || !decisionId(candidateSetId, expected.candidateId()).equals(member.decisionId())
                    || !futureMemoryId(candidateSetId, expected).equals(member.futureMemoryId())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
            }
        }

        List<CandidateEvidenceMapping> mappings = candidateSetPort.findCandidateEvidenceMappings(candidateSetId);
        Map<UUID, AnchorSpec> anchorById = indexAnchorsById(request);
        for (Candidate candidate : request.candidates()) {
            if (!"ACCEPTED".equals(candidate.disposition())) {
                continue;
            }
            LinkedHashSet<UUID> expectedCanonical = new LinkedHashSet<>();
            for (UUID callerAnchorId : candidate.evidenceAnchorIds()) {
                AnchorSpec spec = anchorById.get(callerAnchorId);
                expectedCanonical.add(deterministicId("candidate-set:anchor", candidateSetId, boundaryKey(spec)));
            }
            Set<UUID> actualAnchors = new HashSet<>();
            for (CandidateEvidenceMapping mapping : mappings) {
                if (mapping.candidateId().equals(candidate.candidateId())) {
                    actualAnchors.add(mapping.anchorId());
                }
            }
            if (!actualAnchors.equals(expectedCanonical)) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
            }
        }

        for (Candidate candidate : request.candidates()) {
            Decision verdict = memoryPort.findDecisionByIdempotencyKey(
                    "cs-verdict-" + candidateSetId + "-" + candidate.candidateId());
            String expectedKind = "ACCEPTED".equals(candidate.disposition()) ? "USER_CONFIRM" : "USER_REJECT";
            if (verdict == null
                    || !expectedKind.equals(verdict.decisionKind())
                    || !set.reviewSessionId().equals(verdict.reviewSessionId())
                    || !proposalRevisionId(candidateSetId, candidate.candidateId())
                            .equals(verdict.proposalRevisionId())) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED);
            }
        }

        return new LocalV1CandidateSetResult(
                candidateSetId,
                set.reviewSessionId(),
                "CANONICAL_COMMITTED",
                request.candidates().stream()
                        .map(c -> new CandidateOutcome(
                                c.candidateId(), c.ordinal(), c.disposition(), c.action(), futureMemoryId(candidateSetId, c)))
                        .toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static UUID futureMemoryId(UUID candidateSetId, Candidate candidate) {
        if ("REVISE".equals(candidate.action())) {
            return candidate.targetMemoryId();
        }
        return deterministicId("candidate-set:memory", candidateSetId, candidate.candidateId());
    }

    private static UUID proposalRevisionId(UUID candidateSetId, UUID candidateId) {
        return deterministicId("candidate-set:proposal-rev", candidateSetId, candidateId);
    }

    private static UUID decisionId(UUID candidateSetId, UUID candidateId) {
        return deterministicId("candidate-set:decision", candidateSetId, candidateId);
    }

    private static Map<UUID, Candidate> indexCandidatesById(LocalV1CandidateSetRequest request) {
        Map<UUID, Candidate> map = new HashMap<>();
        for (Candidate c : request.candidates()) {
            map.put(c.candidateId(), c);
        }
        return map;
    }

    private static Map<UUID, AnchorSpec> indexAnchorsById(LocalV1CandidateSetRequest request) {
        Map<UUID, AnchorSpec> map = new HashMap<>();
        for (AnchorSpec a : request.evidencePool().anchors()) {
            map.put(a.anchorId(), a);
        }
        return map;
    }

    private static Map<UUID, EvidenceMessage> indexMessagesById(LocalV1CandidateSetRequest request) {
        Map<UUID, EvidenceMessage> map = new HashMap<>();
        for (EvidenceMessage m : request.evidencePool().messages()) {
            map.put(m.sourceUnitId(), m);
        }
        return map;
    }

    private static List<AnchorSpec> acceptedAnchorSpecs(List<Candidate> accepted, Map<UUID, AnchorSpec> anchorById) {
        LinkedHashSet<UUID> callerIds = new LinkedHashSet<>();
        for (Candidate c : accepted) {
            callerIds.addAll(c.evidenceAnchorIds());
        }
        List<AnchorSpec> specs = new ArrayList<>();
        for (UUID callerId : callerIds) {
            AnchorSpec spec = anchorById.get(callerId);
            if (spec == null) {
                throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
            }
            specs.add(spec);
        }
        return specs;
    }

    private static String boundaryKey(AnchorSpec spec) {
        List<AnchorUnit> sorted = spec.units().stream()
                .sorted(Comparator.comparingLong(AnchorUnit::ordinal))
                .toList();
        StringBuilder sb = new StringBuilder();
        for (AnchorUnit unit : sorted) {
            sb.append(unit.sourceUnitId())
                    .append('|')
                    .append(unit.fromOffset())
                    .append('|')
                    .append(unit.toOffset())
                    .append('|')
                    .append(unit.ordinal())
                    .append(';');
        }
        return sb.toString();
    }

    private static UUID deterministicId(String label, Object... parts) {
        StringBuilder sb = new StringBuilder(label);
        for (Object part : parts) {
            sb.append(':').append(part);
        }
        return UUID.nameUUIDFromBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.INTERNAL_FAILURE, e);
        }
    }

    private void compensateCreatedPayloads(List<PayloadPutResult> putResults, RuntimeException cause) {
        for (PayloadPutResult pr : putResults) {
            if (!pr.created()) {
                continue;
            }
            try {
                payloadStore.delete(pr.objectRef(), pr.contentHash());
            } catch (RuntimeException deleteFailure) {
                cause.addSuppressed(deleteFailure);
            }
        }
    }

    private static String outboxManifest(UUID aggregateId, long revisionNo, String purpose, byte[] hash) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":" + revisionNo
                + ",\"policyRevision\":1,\"purpose\":\"" + purpose + "\",\"manifestHash\":\""
                + hex(hash) + "\"}";
    }

    private static String receiptManifest(UUID candidateSetId) {
        return "{\"type\":\"urn:pink:response:local-v1-candidate-set\",\"status\":200,"
                + "\"requestId\":\"" + candidateSetId
                + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    private static String noCandidatesManifest(UUID candidateSetId) {
        return "{\"type\":\"urn:pink:response:local-v1-candidate-set\",\"status\":200,"
                + "\"requestId\":\"" + candidateSetId
                + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
