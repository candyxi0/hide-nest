package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;
import io.github.candyxi0.hidenest.application.model.CanonicalRevisionRequest;
import io.github.candyxi0.hidenest.application.model.CanonicalRevisionResult;
import io.github.candyxi0.hidenest.memory.domain.*;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class CanonicalRevisionCoordinator {

    private final MemoryGovernancePort memoryPort;
    private final RuntimeTransactionPort runtimePort;
    private final TransactionExecutor tx;
    private final Clock clock;

    public CanonicalRevisionCoordinator(
            MemoryGovernancePort memoryPort,
            RuntimeTransactionPort runtimePort,
            TransactionExecutor tx,
            Clock clock) {
        this.memoryPort = memoryPort;
        this.runtimePort = runtimePort;
        this.tx = tx;
        this.clock = clock;
    }

    public CanonicalRevisionResult revise(CanonicalRevisionRequest request) {
        return tx.executeInTransaction(() -> {
            // L0: Transaction-level advisory lock (R1-04)
            runtimePort.lockIdempotencyKey(request.idempotencyKey());

            // L0: Check idempotency receipt
            IdempotencyReceipt existing = runtimePort.findReceiptByKey(request.idempotencyKey());
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                    return replayResult(existing);
                }
                throw new CanonicalPublishException(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED);
            }

            // L2: Lock and verify decisions with semantic binding (R1-03)
            List<Decision> decisions = memoryPort.lockAndVerifyDecisions(
                    request.decisionIds(), request.reviewSessionId(),
                    request.proposalRevisionId(), "MEMORY", request.memoryId(),
                    request.expectedRevisionNo() != null
                            ? request.expectedRevisionNo() + 1 : null);
            if (decisions.isEmpty()) {
                throw new CanonicalPublishException(CanonicalFailureCode.REVIEW_MEMBER_MISMATCH);
            }

            // L2: Verify proposal revision and review session (only COMPLETED per R1-02)
            ProposalRevision proposalRev =
                    memoryPort.findProposalRevisionById(request.proposalRevisionId());
            if (proposalRev == null) {
                throw new CanonicalPublishException(CanonicalFailureCode.PROPOSAL_CONFLICT);
            }

            ReviewSession reviewSession =
                    memoryPort.findReviewSessionById(request.reviewSessionId());
            if (reviewSession == null || !"COMPLETED".equals(reviewSession.state())) {
                throw new CanonicalPublishException(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN);
            }

            // L4: Lock memory record (R1-05)
            MemoryRecord record = memoryPort.lockMemoryRecordForWrite(request.memoryId());
            if (record == null || !"ACTIVE".equals(record.state())) {
                throw new CanonicalPublishException(CanonicalFailureCode.EXPECTED_REVISION_STALE);
            }

            // L4: Lock and verify current MemoryRevision (R1-05 CAS)
            MemoryRevision currentRev =
                    memoryPort.lockMemoryRevisionForWrite(request.memoryId());
            if (currentRev == null) {
                throw new CanonicalPublishException(CanonicalFailureCode.EXPECTED_REVISION_STALE);
            }
            if (request.expectedRevisionNo() != null
                    && !request.expectedRevisionNo().equals(currentRev.revisionNo())) {
                throw new CanonicalPublishException(CanonicalFailureCode.EXPECTED_REVISION_STALE);
            }

            // L5: Lock and verify access policy
            AccessPolicy policy = memoryPort.lockAccessPolicyForWrite(record.policyId());
            if (policy == null) {
                throw new CanonicalPublishException(CanonicalFailureCode.POLICY_REVISION_STALE);
            }
            if (request.expectedPolicyRevisionNo() != null
                    && !request.expectedPolicyRevisionNo().equals(policy.currentRevisionNo())) {
                throw new CanonicalPublishException(CanonicalFailureCode.POLICY_REVISION_STALE);
            }

            // R1-09: Non-empty relations fail closed
            if (request.relations() != null && !request.relations().isEmpty()) {
                throw new CanonicalPublishException(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }

            Long newRevisionNo = currentRev.revisionNo() + 1;
            Decision primaryDecision = decisions.iterator().next();
            OffsetDateTime now = OffsetDateTime.now(clock);
            UUID revisionId = UUID.randomUUID();
            UUID changeEventId = UUID.randomUUID();
            byte[] manifestHash = request.manifestHash();

            // L5: Optionally create new policy revision
            Long newPolicyRevisionNo = policy.currentRevisionNo();
            if (request.policyChange()) {
                newPolicyRevisionNo = policy.currentRevisionNo() + 1;
                memoryPort.insertAccessPolicyRevision(new AccessPolicyRevision(
                        policy.policyId(), newPolicyRevisionNo,
                        true, true, false, false, false,
                        primaryDecision.decisionId(), now));

                // B09: Policy change governed outbox
                UUID pCeId = UUID.randomUUID();
                memoryPort.insertChangeEvent(new ChangeEvent(
                        pCeId, null, "memory.policy-changed.v1",
                        primaryDecision.actorId(), "ACCESS_POLICY",
                        policy.policyId(), newPolicyRevisionNo,
                        primaryDecision.decisionId(), now, null));
                runtimePort.insertGovernedOutbox(new OutboxEvent(
                        UUID.randomUUID(), request.idempotencyKey() + "-p",
                        null, "GOVERNED", "memory.policy-changed.v1",
                        "ACCESS_POLICY", policy.policyId(), newPolicyRevisionNo,
                        "pink.event.v1", "POLICY_SYNC", newPolicyRevisionNo,
                        manifestHash,
                        buildPayloadManifest(policy.policyId(), newPolicyRevisionNo,
                                newPolicyRevisionNo, "POLICY_SYNC", manifestHash),
                        pCeId, "READY", now,
                        null, null, (short) 0, (short) 8, null, now, null));
            }

            // L4: Insert new revision (old revision NEVER modified in-place)
            memoryPort.insertMemoryRevision(new MemoryRevision(
                    revisionId, request.memoryId(), newRevisionNo,
                    request.memoryType(), request.perspectiveActorId(),
                    request.bodyText(), null, null, null,
                    primaryDecision.decisionId(), now));

            // L8: Create change event
            memoryPort.insertChangeEvent(new ChangeEvent(
                    changeEventId, null, "memory.canonical-committed.v1",
                    primaryDecision.actorId(), "MEMORY",
                    request.memoryId(), newRevisionNo,
                    primaryDecision.decisionId(), now,
                    buildDetailManifest(revisionId, manifestHash)));

            // L8: Create governed outbox
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    UUID.randomUUID(), request.idempotencyKey() + "-c",
                    null, "GOVERNED", "memory.canonical-committed.v1",
                    "MEMORY", request.memoryId(), newRevisionNo,
                    "pink.event.v1", "CANONICAL_SYNC", newPolicyRevisionNo,
                    manifestHash,
                    buildPayloadManifest(request.memoryId(), newRevisionNo,
                            newPolicyRevisionNo, "CANONICAL_SYNC", manifestHash),
                    changeEventId, "READY", now,
                    null, null, (short) 0, (short) 8, null, now, null));

            // Update current pointer with CAS (R1-05)
            boolean updated = memoryPort.updateMemoryRecordPointerCAS(
                    request.memoryId(),
                    currentRev.memoryRevisionId(), // expected revision
                    revisionId, // new revision
                    policy.policyId(),
                    request.expectedPolicyRevisionNo(), // expected policy
                    newPolicyRevisionNo);
            if (!updated) {
                throw new CanonicalPublishException(CanonicalFailureCode.EXPECTED_REVISION_STALE);
            }

            // L0: Commit receipt
            String responseManifest = buildResponseManifest(revisionId);
            runtimePort.commitReceipt(
                    request.idempotencyKey(), "CANONICAL_REVISE",
                    request.requestHash(), request.memoryId(), "MEMORY",
                    responseManifest);

            return CanonicalRevisionResult.success(request.memoryId(), revisionId, newRevisionNo);
        });
    }

    private CanonicalRevisionResult replayResult(IdempotencyReceipt receipt) {
        UUID memoryId = receipt.resourceId();
        MemoryRecord record = memoryPort.lockMemoryRecordForWrite(memoryId);
        if (record != null && record.currentRevisionId() != null) {
            MemoryRevision rev = memoryPort.lockMemoryRevisionForWrite(memoryId);
            if (rev != null) {
                return CanonicalRevisionResult.success(memoryId,
                        rev.memoryRevisionId(), rev.revisionNo());
            }
        }
        return CanonicalRevisionResult.success(memoryId, null, null);
    }

    private String buildDetailManifest(UUID revisionId, byte[] manifestHash) {
        return "{\"memoryRevisionId\":\"" + revisionId + "\",\"manifestHash\":\""
                + CanonicalPublishCoordinator.bytesToHex(manifestHash) + "\"}";
    }

    private String buildPayloadManifest(UUID aggregateId, Long revisionNo,
            Long policyRevisionNo, String purpose, byte[] manifestHash) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":"
                + revisionNo + ",\"policyRevision\":" + policyRevisionNo
                + ",\"purpose\":\"" + purpose + "\",\"manifestHash\":\""
                + CanonicalPublishCoordinator.bytesToHex(manifestHash) + "\"}";
    }

    private String buildResponseManifest(UUID revisionId) {
        return "{\"type\":\"urn:pink:response:canonical-revise\",\"status\":200,"
                + "\"requestId\":\"" + revisionId + "\","
                + "\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }
}
