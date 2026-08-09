package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;
import io.github.candyxi0.hidenest.application.model.CanonicalPublishRequest;
import io.github.candyxi0.hidenest.application.model.CanonicalPublishResult;
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

public class CanonicalPublishCoordinator {

    private final MemoryGovernancePort memoryPort;
    private final RuntimeTransactionPort runtimePort;
    private final TransactionExecutor tx;
    private final Clock clock;

    public CanonicalPublishCoordinator(
            MemoryGovernancePort memoryPort,
            RuntimeTransactionPort runtimePort,
            TransactionExecutor tx,
            Clock clock) {
        this.memoryPort = memoryPort;
        this.runtimePort = runtimePort;
        this.tx = tx;
        this.clock = clock;
    }

    public CanonicalPublishResult publishFirst(CanonicalPublishRequest request) {
        return tx.executeInTransaction(() -> {
            // L0: Transaction-level advisory lock for idempotency key (R1-04)
            runtimePort.lockIdempotencyKey(request.idempotencyKey());

            // L0: Check idempotency receipt
            IdempotencyReceipt existing = runtimePort.findReceiptByKey(request.idempotencyKey());
            if (existing != null) {
                if (Arrays.equals(existing.requestHash(), request.requestHash())) {
                    return replayResult(existing);
                }
                throw new CanonicalPublishException(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED);
            }

            // L2: Lock and verify decisions with semantic binding (R1-02, R1-03)
            List<Decision> decisions = memoryPort.lockAndVerifyDecisions(
                    request.decisionIds(), request.reviewSessionId(),
                    request.proposalRevisionId(), "MEMORY", request.memoryId(), 1L);
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

            // L4: Verify memory record does not already exist
            MemoryRecord existingRecord = memoryPort.lockMemoryRecordForWrite(request.memoryId());
            if (existingRecord != null) {
                throw new CanonicalPublishException(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }

            // R1-09: Non-empty relations fail closed
            if (request.relations() != null && !request.relations().isEmpty()) {
                throw new CanonicalPublishException(CanonicalFailureCode.CANONICAL_COMMIT_FAILED);
            }

            Decision primaryDecision = decisions.iterator().next();
            OffsetDateTime now = OffsetDateTime.now(clock);
            UUID revisionId = UUID.randomUUID();
            UUID changeEventId = UUID.randomUUID();
            byte[] manifestHash = request.manifestHash();

            // L5: Insert access_policy root (deferred FK to revision)
            memoryPort.insertAccessPolicy(new AccessPolicy(
                    request.policyId(), "MEMORY", request.memoryId(), 1L, now));

            // L5: Insert access_policy_revision (immediate FK to policy)
            memoryPort.insertAccessPolicyRevision(new AccessPolicyRevision(
                    request.policyId(), 1L, true, true, false, false, false,
                    primaryDecision.decisionId(), now));

            // B09: Policy change event + governed outbox
            UUID policyCeId = UUID.randomUUID();
            memoryPort.insertChangeEvent(new ChangeEvent(
                    policyCeId, null, "memory.policy-changed.v1",
                    primaryDecision.actorId(), "ACCESS_POLICY",
                    request.policyId(), 1L,
                    primaryDecision.decisionId(), now, null));
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    UUID.randomUUID(), request.idempotencyKey() + "-p",
                    null, "GOVERNED", "memory.policy-changed.v1",
                    "ACCESS_POLICY", request.policyId(), 1L,
                    "pink.event.v1", "POLICY_SYNC", 1L, manifestHash,
                    buildPayloadManifest(request.policyId(), 1L, 1L, "POLICY_SYNC", manifestHash),
                    policyCeId, "READY", now,
                    null, null, (short) 0, (short) 8, null, now, null));

            // L4: Insert memory_record with current_revision_id (deferred FK)
            memoryPort.insertMemoryRecord(new MemoryRecord(
                    request.memoryId(), "ACTIVE", revisionId,
                    request.policyId(), 1L, now, now));

            // L4: Insert memory_revision
            memoryPort.insertMemoryRevision(new MemoryRevision(
                    revisionId, request.memoryId(), 1L,
                    request.memoryType(), request.perspectiveActorId(),
                    request.bodyText(), null, null, null,
                    primaryDecision.decisionId(), now));

            // L8: Create change event
            memoryPort.insertChangeEvent(new ChangeEvent(
                    changeEventId, null, "memory.canonical-committed.v1",
                    primaryDecision.actorId(), "MEMORY",
                    request.memoryId(), 1L,
                    primaryDecision.decisionId(), now,
                    buildDetailManifest(revisionId, manifestHash)));

            // L8: Create governed outbox
            runtimePort.insertGovernedOutbox(new OutboxEvent(
                    UUID.randomUUID(), request.idempotencyKey() + "-c",
                    null, "GOVERNED", "memory.canonical-committed.v1",
                    "MEMORY", request.memoryId(), 1L,
                    "pink.event.v1", "CANONICAL_SYNC", 1L, manifestHash,
                    buildPayloadManifest(request.memoryId(), 1L, 1L, "CANONICAL_SYNC", manifestHash),
                    changeEventId, "READY", now,
                    null, null, (short) 0, (short) 8, null, now, null));

            // L0: Commit receipt with all resource fields
            String responseManifest = buildResponseManifest(revisionId);
            runtimePort.commitReceipt(
                    request.idempotencyKey(), "CANONICAL_PUBLISH",
                    request.requestHash(), request.memoryId(), "MEMORY",
                    responseManifest);

            return CanonicalPublishResult.success(request.memoryId(), revisionId, 1L);
        });
    }

    /** Replay: read memory_record for current revision fields (R1-10). */
    private CanonicalPublishResult replayResult(IdempotencyReceipt receipt) {
        UUID memoryId = receipt.resourceId();
        // Read current pointer from database (outside this tx, but inside outer tx)
        MemoryRecord record = memoryPort.lockMemoryRecordForWrite(memoryId);
        if (record != null && record.currentRevisionId() != null) {
            MemoryRevision rev = memoryPort.lockMemoryRevisionForWrite(memoryId);
            if (rev != null) {
                return CanonicalPublishResult.success(memoryId,
                        rev.memoryRevisionId(), rev.revisionNo());
            }
        }
        return CanonicalPublishResult.success(memoryId, null, null);
    }

    private String buildDetailManifest(UUID revisionId, byte[] manifestHash) {
        return "{\"memoryRevisionId\":\"" + revisionId + "\",\"manifestHash\":\""
                + bytesToHex(manifestHash) + "\"}";
    }

    private String buildPayloadManifest(UUID aggregateId, Long revisionNo,
            Long policyRev, String purpose, byte[] manifestHash) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":"
                + revisionNo + ",\"policyRevision\":" + policyRev
                + ",\"purpose\":\"" + purpose + "\",\"manifestHash\":\""
                + bytesToHex(manifestHash) + "\"}";
    }

    /** Response manifest using only allowed keys (R1-10). */
    private String buildResponseManifest(UUID revisionId) {
        return "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                + "\"requestId\":\"" + revisionId + "\","
                + "\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
