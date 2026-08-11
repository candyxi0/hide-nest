package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.*;
import io.github.candyxi0.hidenest.evidence.domain.*;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.memory.domain.*;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

public class LocalV1S1WindowCloseCoordinator {

    private static final String PLATFORM = "LOCAL_V1";

    private final EvidenceReferencePort evidencePort;
    private final MemoryGovernancePort memoryPort;
    private final RuntimeTransactionPort runtimePort;
    private final TransactionExecutor tx;
    private final CanonicalPublishCoordinator canonicalPublish;
    private final Clock clock;

    public LocalV1S1WindowCloseCoordinator(
            EvidenceReferencePort evidencePort,
            MemoryGovernancePort memoryPort,
            RuntimeTransactionPort runtimePort,
            TransactionExecutor tx,
            CanonicalPublishCoordinator canonicalPublish,
            Clock clock) {
        this.evidencePort = evidencePort;
        this.memoryPort = memoryPort;
        this.runtimePort = runtimePort;
        this.tx = tx;
        this.canonicalPublish = canonicalPublish;
        this.clock = clock;
    }

    // ── prepare ──────────────────────────────────────────────────────────

    public LocalV1S1PrepareResult prepare(LocalV1S1PrepareRequest request) {
        return tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(request.idempotencyKey());

            IdempotencyReceipt existing = runtimePort.findReceiptByKey(
                    request.idempotencyKey());
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                    return replayPrepare(existing);
                }
                throw new LocalV1S1Exception(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED);
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            UUID sourceId = UUID.randomUUID();
            UUID sourcePolicyId = UUID.randomUUID();
            UUID proposalId = UUID.randomUUID();
            UUID proposalRevisionId = UUID.randomUUID();
            UUID reviewSessionId = UUID.randomUUID();
            UUID hideSelectDecisionId = UUID.randomUUID();
            UUID hideSystemActorId = UUID.randomUUID();

            // R1-04: HIDE_SELECT uses a distinct system actor, not 小林
            memoryPort.insertActorRef(new ActorRef(
                    hideSystemActorId, "SYNTHETIC",
                    "hs-" + hideSystemActorId, "hide", now));

            // R1-01: only insert actor_refs for evidence message actors
            Set<UUID> seenActors = new HashSet<>();
            for (var msg : request.selectedEvidenceMessages()) {
                if (seenActors.add(msg.actorId())) {
                    memoryPort.insertActorRef(new ActorRef(
                            msg.actorId(), "SYNTHETIC",
                            "a-" + msg.actorId(),
                            msg.actorId().equals(request.perspectiveActorId())
                                    ? "小林" : "协作者",
                            now));
                }
            }

            // HIDE_SELECT Decision (system actor, not 小林)
            memoryPort.insertDecision(new Decision(
                    hideSelectDecisionId, "HIDE_SELECT",
                    hideSystemActorId, "USER",
                    null, null,
                    "ACCESS_POLICY", sourcePolicyId, 1L,
                    "hide-select", "hs-" + request.idempotencyKey(), now));

            // Access policy for the source
            memoryPort.insertAccessPolicy(new AccessPolicy(
                    sourcePolicyId, "SOURCE", sourceId, 1L, now));
            memoryPort.insertAccessPolicyRevision(new AccessPolicyRevision(
                    sourcePolicyId, 1L, true, true, false, false, false,
                    hideSelectDecisionId, now));

            // Policy governance artifacts
            UUID policyCeId = UUID.randomUUID();
            byte[] emptyHash = new byte[32];
            memoryPort.insertChangeEvent(new ChangeEvent(
                    policyCeId, null, "memory.policy-changed.v1",
                    hideSystemActorId, "ACCESS_POLICY",
                    sourcePolicyId, 1L,
                    hideSelectDecisionId, now, null));
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    UUID.randomUUID(), request.idempotencyKey() + "-src-pol",
                    null, "GOVERNED", "memory.policy-changed.v1",
                    "ACCESS_POLICY", sourcePolicyId, 1L,
                    "pink.event.v1", "POLICY_SYNC", 1L, emptyHash,
                    buildOutboxPayloadManifest(sourcePolicyId, 1L,
                            "POLICY_SYNC", emptyHash),
                    policyCeId, "READY", now,
                    null, null, (short) 0, (short) 8, null, now, null));

            // R1-03: deterministic Source.externalRef = "review:" + reviewSessionId
            String sourceExternalRef = "review:" + reviewSessionId;
            evidencePort.insertSource(new Source(
                    sourceId, "SYNTHETIC_CONVERSATION", PLATFORM,
                    sourceExternalRef, false, false,
                    sourcePolicyId, now, now));

            // R1-01: only create SourceUnits for SELECTED evidence messages
            for (var msg : request.selectedEvidenceMessages()) {
                evidencePort.insertSourceUnit(new SourceUnit(
                        msg.sourceUnitId(), sourceId, msg.externalUnitRef(),
                        "v1", msg.ordinal(), msg.actorId(),
                        msg.occurredAt(), now));
            }

            // Create SourceAnchors and SourceAnchorUnits for evidence
            Set<UUID> anchorIds = new LinkedHashSet<>();
            for (var anchor : request.anchors()) {
                anchorIds.add(anchor.anchorId());
                evidencePort.insertSourceAnchor(new SourceAnchor(
                        anchor.anchorId(), sourceId, "MESSAGE_SEGMENT", now));
                List<SourceAnchorUnit> anchorUnits = new ArrayList<>();
                for (var unit : anchor.units()) {
                    anchorUnits.add(new SourceAnchorUnit(
                            anchor.anchorId(), unit.sourceUnitId(),
                            unit.fromOffset(), unit.toOffset(), unit.ordinal()));
                }
                if (!anchorUnits.isEmpty()) {
                    evidencePort.insertSourceAnchorUnits(anchorUnits);
                }
            }

            // Proposal (CREATE, target_memory_id=null)
            memoryPort.insertProposal(new Proposal(
                    proposalId, "CREATE", null, now));
            memoryPort.insertProposalRevision(new ProposalRevision(
                    proposalRevisionId, proposalId, 1L, "PUBLISH",
                    request.bodyText(), request.memoryType(),
                    request.perspectiveActorId(), null, null,
                    request.bodyHash(), now));

            // ReviewSession (OPEN)
            memoryPort.insertReviewSession(new ReviewSession(
                    reviewSessionId, "OPEN",
                    "rv-" + request.idempotencyKey(), request.requestHash(),
                    now, null));
            memoryPort.insertReviewMember(new ReviewMember(
                    reviewSessionId, proposalRevisionId, 1L));

            // Commit receipt
            runtimePort.commitReceipt(
                    request.idempotencyKey(), "LOCAL_V1_S1_PREPARE",
                    request.requestHash(), reviewSessionId, "REVIEW_SESSION",
                    buildPrepareResponseManifest(reviewSessionId, proposalRevisionId));

            return new LocalV1S1PrepareResult(
                    sourceId, reviewSessionId, proposalRevisionId,
                    Set.of(hideSelectDecisionId), anchorIds,
                    request.bodyText(), request.memoryType(),
                    request.perspectiveActorId(), "PREPARED");
        });
    }

    // ── confirm ──────────────────────────────────────────────────────────

    public LocalV1S1ConfirmResult confirm(LocalV1S1ConfirmRequest request) {
        return tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(request.idempotencyKey());

            IdempotencyReceipt existing = runtimePort.findReceiptByKey(
                    request.idempotencyKey());
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                    return replayConfirm(existing);
                }
                throw new LocalV1S1Exception(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED);
            }

            OffsetDateTime now = OffsetDateTime.now(clock);

            // Lock and verify review session is OPEN
            ReviewSession session = memoryPort.lockReviewSessionForWrite(
                    request.reviewSessionId());
            if (session == null || !"OPEN".equals(session.state())) {
                throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
            }

            // R1-04: exact ReviewMember set match (not anyMatch)
            List<ReviewMember> members = memoryPort.findReviewMembersBySessionId(
                    request.reviewSessionId());
            if (members.size() != 1
                    || !members.get(0).proposalRevisionId()
                            .equals(request.proposalRevisionId())) {
                throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_MEMBER_MISMATCH);
            }

            // Lock and read ProposalRevision (authoritative source of content)
            ProposalRevision proposalRev = memoryPort.findProposalRevisionById(
                    request.proposalRevisionId());
            if (proposalRev == null) {
                throw new LocalV1S1Exception(CanonicalFailureCode.PROPOSAL_CONFLICT);
            }
            // R1-02: derive body, type, perspective from ProposalRevision
            String bodyText = proposalRev.bodyText();
            String memoryType = proposalRev.memoryType();
            UUID perspectiveActorId = proposalRev.perspectiveActorId();

            // R1-03: derive evidence anchors from deterministic Source binding
            String sourceExternalRef = "review:" + request.reviewSessionId();
            Source source = evidencePort.findSourceByExternalRef(
                    PLATFORM, sourceExternalRef);
            if (source == null) {
                throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }
            List<SourceAnchor> sourceAnchors =
                    evidencePort.findSourceAnchorsBySourceId(source.sourceId());
            if (sourceAnchors.isEmpty()) {
                throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }

            // Validate all anchors belong to this source and are unique
            Set<UUID> derivedAnchorIds = new LinkedHashSet<>();
            for (var anchor : sourceAnchors) {
                if (!anchor.sourceId().equals(source.sourceId())) {
                    throw new LocalV1S1Exception(
                            CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
                }
                if (!derivedAnchorIds.add(anchor.anchorId())) {
                    throw new LocalV1S1Exception(
                            CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
                }
            }

            // R1-04: USER_CONFIRM actor = perspectiveActorId from ProposalRevision
            // (identity continuity, not a new random 小林)
            UUID decisionActorId = perspectiveActorId;

            UUID userConfirmDecisionId = UUID.randomUUID();
            UUID reviewCeId = UUID.randomUUID();

            // Insert USER_CONFIRM Decision
            memoryPort.insertDecision(new Decision(
                    userConfirmDecisionId, "USER_CONFIRM",
                    decisionActorId, "HUMAN",
                    request.proposalRevisionId(), request.reviewSessionId(),
                    "MEMORY", request.memoryId(), 1L,
                    "user-confirm", "uc-" + request.idempotencyKey(), now));

            // Change event + governed outbox for review.decisions-committed.v1
            memoryPort.insertChangeEvent(new ChangeEvent(
                    reviewCeId, null, "review.decisions-committed.v1",
                    decisionActorId, "REVIEW_SESSION",
                    request.reviewSessionId(), 1L,
                    userConfirmDecisionId, now, null));
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    UUID.randomUUID(), "ob-" + userConfirmDecisionId,
                    null, "GOVERNED", "review.decisions-committed.v1",
                    "REVIEW_SESSION", request.reviewSessionId(), 1L,
                    "pink.event.v1", "REVIEW_SYNC", 1L,
                    request.manifestHash(),
                    buildOutboxPayloadManifest(request.reviewSessionId(), 1L,
                            "REVIEW_SYNC", request.manifestHash()),
                    reviewCeId, "READY", now,
                    null, null, (short) 0, (short) 8, null, now, null));

            // Transition review session to COMPLETED
            boolean transitioned = memoryPort.transitionReviewSessionState(
                    request.reviewSessionId(), "OPEN", "COMPLETED", now);
            if (!transitioned) {
                throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
            }

            // R1-03: EVIDENCED_BY relations from derived anchor set (not from request)
            List<CanonicalPublishRequest.RelationSpec> relationSpecs = new ArrayList<>();
            for (UUID anchorId : derivedAnchorIds) {
                relationSpecs.add(new CanonicalPublishRequest.RelationSpec(
                        "EVIDENCED_BY", null, anchorId, perspectiveActorId));
            }

            // Delegate to CanonicalPublishCoordinator
            CanonicalPublishRequest pubRequest = new CanonicalPublishRequest(
                    request.idempotencyKey() + "-mem",
                    request.requestHash(),
                    Set.of(userConfirmDecisionId),
                    request.proposalRevisionId(),
                    request.reviewSessionId(),
                    request.memoryId(),
                    memoryType,
                    perspectiveActorId,
                    bodyText,
                    request.policyId(),
                    relationSpecs,
                    request.manifestHash());

            CanonicalPublishResult pubResult = canonicalPublish.publishFirst(pubRequest);

            int evidenceCount = derivedAnchorIds.size();

            // Commit confirm receipt
            runtimePort.commitReceipt(
                    request.idempotencyKey(), "LOCAL_V1_S1_CONFIRM",
                    request.requestHash(), request.memoryId(), "MEMORY",
                    buildConfirmResponseManifest(
                            pubResult.memoryId(), pubResult.revisionId(),
                            pubResult.revisionNo(), evidenceCount));

            return LocalV1S1ConfirmResult.success(
                    pubResult.memoryId(), pubResult.revisionId(),
                    pubResult.revisionNo(), evidenceCount);
        });
    }

    // ── reject ───────────────────────────────────────────────────────────

    public void reject(String idempotencyKey, byte[] requestHash,
            UUID reviewSessionId, String reason) {
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(idempotencyKey);

            IdempotencyReceipt existing = runtimePort.findReceiptByKey(idempotencyKey);
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), requestHash)) {
                    return null;
                }
                throw new LocalV1S1Exception(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED);
            }

            OffsetDateTime now = OffsetDateTime.now(clock);

            ReviewSession session = memoryPort.lockReviewSessionForWrite(reviewSessionId);
            if (session == null || !"OPEN".equals(session.state())) {
                throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
            }

            boolean transitioned = memoryPort.transitionReviewSessionState(
                    reviewSessionId, "OPEN", "CANCELLED", now);
            if (!transitioned) {
                throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
            }

            runtimePort.commitReceipt(
                    idempotencyKey, "LOCAL_V1_S1_REJECT",
                    requestHash, reviewSessionId, "REVIEW_SESSION",
                    buildRejectResponseManifest(reviewSessionId, reason));

            return null;
        });
    }

    // ── replay ───────────────────────────────────────────────────────────

    /** R1-05: prepare replay returns exact fields, not null/empty placeholders. */
    private LocalV1S1PrepareResult replayPrepare(IdempotencyReceipt receipt) {
        UUID reviewSessionId = receipt.resourceId();
        ReviewSession session = memoryPort.findReviewSessionById(reviewSessionId);
        if (session == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
        }
        List<ReviewMember> members = memoryPort.findReviewMembersBySessionId(
                reviewSessionId);
        if (members.size() != 1) {
            throw new LocalV1S1Exception(CanonicalFailureCode.REVIEW_MEMBER_MISMATCH);
        }
        UUID proposalRevisionId = members.get(0).proposalRevisionId();
        ProposalRevision rev = memoryPort.findProposalRevisionById(proposalRevisionId);
        if (rev == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.PROPOSAL_CONFLICT);
        }
        // R1-03: find Source via deterministic externalRef
        String sourceExternalRef = "review:" + reviewSessionId;
        Source source = evidencePort.findSourceByExternalRef(PLATFORM, sourceExternalRef);
        if (source == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        List<SourceAnchor> anchors =
                evidencePort.findSourceAnchorsBySourceId(source.sourceId());
        Set<UUID> anchorIds = new LinkedHashSet<>();
        for (var a : anchors) {
            anchorIds.add(a.anchorId());
        }
        Decision hideSelect = memoryPort.findDecisionByIdempotencyKey(
                "hs-" + receipt.idempotencyKey());
        if (hideSelect == null
                || !"HIDE_SELECT".equals(hideSelect.decisionKind())
                || !"ACCESS_POLICY".equals(hideSelect.targetKind())
                || !source.policyId().equals(hideSelect.targetId())
                || !Long.valueOf(1L).equals(hideSelect.targetRevisionRef())) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        return new LocalV1S1PrepareResult(
                source.sourceId(), reviewSessionId, proposalRevisionId,
                Set.of(hideSelect.decisionId()),
                anchorIds, rev.bodyText(), rev.memoryType(),
                rev.perspectiveActorId(), "PREPARED");
    }

    /** R1-05: confirm replay must fail if any artifact missing. */
    private LocalV1S1ConfirmResult replayConfirm(IdempotencyReceipt receipt) {
        UUID memoryId = receipt.resourceId();
        MemoryRecord record = memoryPort.lockMemoryRecordForWrite(memoryId);
        if (record == null || record.currentRevisionId() == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        MemoryRevision rev = memoryPort.lockMemoryRevisionForWrite(memoryId);
        if (rev == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        if (!record.currentRevisionId().equals(rev.memoryRevisionId())
                || !memoryId.equals(rev.memoryId())
                || rev.revisionNo() == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        Decision confirmDecision = memoryPort.findDecisionByIdempotencyKey(
                "uc-" + receipt.idempotencyKey());
        if (confirmDecision == null
                || !"USER_CONFIRM".equals(confirmDecision.decisionKind())
                || !rev.createdByDecisionId().equals(confirmDecision.decisionId())
                || confirmDecision.reviewSessionId() == null
                || confirmDecision.proposalRevisionId() == null
                || !"MEMORY".equals(confirmDecision.targetKind())
                || !memoryId.equals(confirmDecision.targetId())
                || !rev.revisionNo().equals(confirmDecision.targetRevisionRef())) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        Source source = evidencePort.findSourceByExternalRef(
                PLATFORM, "review:" + confirmDecision.reviewSessionId());
        if (source == null) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        Set<UUID> expectedAnchors = new HashSet<>();
        for (SourceAnchor anchor : evidencePort.findSourceAnchorsBySourceId(source.sourceId())) {
            if (!expectedAnchors.add(anchor.anchorId())) {
                throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }
        }
        if (expectedAnchors.isEmpty()) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        List<MemoryRelation> relations =
                memoryPort.findMemoryRelationsByFromRevisionId(rev.memoryRevisionId());
        Set<UUID> actualAnchors = new HashSet<>();
        for (MemoryRelation relation : relations) {
            if (!"EVIDENCED_BY".equals(relation.relationType())
                    || relation.toAnchorId() == null
                    || relation.toRevisionId() != null
                    || !rev.perspectiveActorId().equals(relation.perspectiveActorId())
                    || !confirmDecision.decisionId().equals(relation.createdByDecisionId())
                    || !actualAnchors.add(relation.toAnchorId())) {
                throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }
        }
        if (!actualAnchors.equals(expectedAnchors)) {
            throw new LocalV1S1Exception(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
        }
        return LocalV1S1ConfirmResult.success(
                memoryId, rev.memoryRevisionId(), rev.revisionNo(), actualAnchors.size());
    }

    // ── manifest builders ────────────────────────────────────────────────

    private String buildPrepareResponseManifest(UUID reviewSessionId,
            UUID proposalRevisionId) {
        return "{\"type\":\"urn:pink:response:local-v1-s1-prepare\",\"status\":200,"
                + "\"requestId\":\"" + reviewSessionId + "\","
                + "\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    private String buildConfirmResponseManifest(UUID memoryId, UUID revisionId,
            Long revisionNo, int evidenceCount) {
        return "{\"type\":\"urn:pink:response:local-v1-s1-confirm\",\"status\":200,"
                + "\"requestId\":\"" + memoryId + "\","
                + "\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    private String buildRejectResponseManifest(UUID reviewSessionId, String reason) {
        return "{\"type\":\"urn:pink:response:local-v1-s1-reject\",\"status\":200,"
                + "\"requestId\":\"" + reviewSessionId + "\","
                + "\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    private String buildOutboxPayloadManifest(UUID aggregateId, Long revisionNo,
            String purpose, byte[] manifestHash) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":"
                + revisionNo + ",\"policyRevision\":" + revisionNo
                + ",\"purpose\":\"" + purpose + "\",\"manifestHash\":\""
                + CanonicalPublishCoordinator.bytesToHex(manifestHash) + "\"}";
    }
}
