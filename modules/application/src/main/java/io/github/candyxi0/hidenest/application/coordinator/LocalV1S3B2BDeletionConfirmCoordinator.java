package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmResult;
import io.github.candyxi0.hidenest.memory.domain.Decision;
import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.port.DeletionConfirmationPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** S3B2B application confirm coordinator. */
public final class LocalV1S3B2BDeletionConfirmCoordinator {

    private static final String DECISION_KIND = "USER_DELETE_CONFIRM";
    private static final String TARGET_KIND = "DELETION_CLOSURE";
    private static final String ACTOR_ROLE = "HUMAN";
    private static final String AUTHORIZATION_REF = "local-v1-synthetic-deletion-confirm";
    private static final String AFFECTED = "AFFECTED_PENDING_CHOICE";

    private final DeletionConfirmationPort confirmationPort;
    private final MemoryGovernancePort governancePort;
    private final DeletionFencePort fencePort;
    private final DeletionPreviewPort previewPort;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public LocalV1S3B2BDeletionConfirmCoordinator(
            DeletionConfirmationPort confirmationPort,
            MemoryGovernancePort governancePort,
            DeletionFencePort fencePort,
            DeletionPreviewPort previewPort,
            TransactionExecutor transactions,
            Clock clock) {
        this.confirmationPort = Objects.requireNonNull(confirmationPort, "confirmationPort");
        this.governancePort = Objects.requireNonNull(governancePort, "governancePort");
        this.fencePort = Objects.requireNonNull(fencePort, "fencePort");
        this.previewPort = Objects.requireNonNull(previewPort, "previewPort");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalV1S3B2BDeletionConfirmResult confirm(LocalV1S3B2BDeletionConfirmRequest request) {
        validateRequest(request);
        return transactions.executeInTransaction(() -> confirmInTransaction(request));
    }

    // -- transaction body ---------------------------------------------------

    private LocalV1S3B2BDeletionConfirmResult confirmInTransaction(
            LocalV1S3B2BDeletionConfirmRequest request) {
        // Step 2: read idempotency key before lock
        Decision existingDecision = governancePort.findDecisionByIdempotencyKey(request.idempotencyKey());

        // Step 3: lock closure snapshot
        DeletionConfirmationPort.ConfirmationSnapshot snapshot =
                confirmationPort.lockConfirmationSnapshot(request.closureId());

        // Step 4: closure must exist
        if (snapshot == null) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }

        // Step 5: re-read idempotency key after lock (cover "lock wait" race)
        Decision afterLock = governancePort.findDecisionByIdempotencyKey(request.idempotencyKey());
        if (afterLock != null) {
            if ("CONFIRMED".equals(snapshot.state())) {
                return replayIfMatch(request, snapshot, afterLock);
            }
            // If snapshot is PREVIEWED but a decision exists, this is a conflict
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }

        // Step 5 (continued): if already CONFIRMED with no idempotency key match
        if ("CONFIRMED".equals(snapshot.state())) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }

        // Step 6: PREVIEWED — exact match on revision and manifest
        if (!"PREVIEWED".equals(snapshot.state())) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }
        if (snapshot.previewRevision() != request.previewRevision()
                || !Arrays.equals(snapshot.manifestHash(), request.manifestHash())) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (now.isAfter(snapshot.expiresAt())) {
            throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        }

        // Step 7: canonical re-derivation from current graph
        DeletionPreviewGraph graph = readGraph(snapshot.rootMemoryId());
        verifyCanonicalMatch(snapshot, graph);

        // Step 8: verify actor exists
        if (governancePort.findActorRefById(request.actorId()) == null) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }

        // Step 9: insert Decision
        UUID decisionId = UUID.randomUUID();
        governancePort.insertDecision(new Decision(
                decisionId, DECISION_KIND, request.actorId(), ACTOR_ROLE,
                null, null, TARGET_KIND, request.closureId(), request.previewRevision(),
                AUTHORIZATION_REF, request.idempotencyKey(), now));

        // Step 10: fence non-AFFECTED members by snapshot ordinal order
        List<DeletionConfirmationPort.Member> ordered = snapshot.members().stream()
                .sorted(Comparator.comparingLong(DeletionConfirmationPort.Member::ordinal))
                .toList();
        int fenceCount = 0;
        int affectedCount = 0;
        List<DeletionFencePort.FenceDraft> drafts = new ArrayList<>();
        for (DeletionConfirmationPort.Member member : ordered) {
            if (AFFECTED.equals(member.disposition())) {
                affectedCount++;
                continue;
            }
            drafts.add(new DeletionFencePort.FenceDraft(
                    UUID.randomUUID(), snapshot.closureId(), member.memberKind(),
                    member.targetId(), member.targetRevisionRef(), decisionId, now));
            fenceCount++;
        }
        if (!drafts.isEmpty()) {
            fencePort.insertFences(drafts);
        }

        // Step 11: CAS confirmation
        boolean confirmed = confirmationPort.confirmClosure(
                snapshot.closureId(), snapshot.previewRevision(), snapshot.manifestHash(),
                snapshot.rootCurrentRevisionId(), snapshot.rootRevisionNo(),
                snapshot.rootPolicyId(), snapshot.rootPolicyRevisionNo(),
                decisionId, now);
        if (!confirmed) {
            throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        }

        // Step 12: return result
        return new LocalV1S3B2BDeletionConfirmResult(
                snapshot.closureId(), snapshot.previewRevision(), snapshot.manifestHash(),
                decisionId, now.withOffsetSameInstant(ZoneOffset.UTC), "CONFIRMED",
                fenceCount, affectedCount);
    }

    // -- idempotent replay ---------------------------------------------------

    private LocalV1S3B2BDeletionConfirmResult replayIfMatch(
            LocalV1S3B2BDeletionConfirmRequest request,
            DeletionConfirmationPort.ConfirmationSnapshot snapshot,
            Decision decision) {
        if (!DECISION_KIND.equals(decision.decisionKind())
                || !request.actorId().equals(decision.actorId())
                || !ACTOR_ROLE.equals(decision.actorRole())
                || decision.proposalRevisionId() != null
                || decision.reviewSessionId() != null
                || !TARGET_KIND.equals(decision.targetKind())
                || !request.closureId().equals(decision.targetId())
                || !Objects.equals(request.previewRevision(), decision.targetRevisionRef())
                || !AUTHORIZATION_REF.equals(decision.authorizationRef())
                || !request.idempotencyKey().equals(decision.idempotencyKey())
                || !Arrays.equals(request.manifestHash(), snapshot.manifestHash())
                || snapshot.confirmedByDecisionId() == null
                || !snapshot.confirmedByDecisionId().equals(decision.decisionId())
                || snapshot.confirmedAt() == null) {
            throw failure(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH);
        }
        // Re-derive fence count from current state
        int fenceCount = 0;
        int affectedCount = 0;
        for (DeletionConfirmationPort.Member member : snapshot.members()) {
            if (AFFECTED.equals(member.disposition())) {
                affectedCount++;
            } else {
                fenceCount++;
            }
        }
        return new LocalV1S3B2BDeletionConfirmResult(
                snapshot.closureId(), snapshot.previewRevision(), snapshot.manifestHash(),
                decision.decisionId(), snapshot.confirmedAt().withOffsetSameInstant(ZoneOffset.UTC),
                "CONFIRMED", fenceCount, affectedCount);
    }

    // -- canonical verification ----------------------------------------------

    private void verifyCanonicalMatch(
            DeletionConfirmationPort.ConfirmationSnapshot snapshot,
            DeletionPreviewGraph graph) {
        // Verify root pointers unchanged
        if (!snapshot.rootCurrentRevisionId().equals(graph.rootMemory().currentRevisionId())
                || snapshot.rootRevisionNo() != graph.currentRevision().revisionNo()
                || !snapshot.rootPolicyId().equals(graph.rootMemory().policyId())
                || snapshot.rootPolicyRevisionNo() != graph.rootMemory().currentPolicyRevisionNo()
                || !snapshot.rootMemoryId().equals(graph.rootMemory().memoryId())
                || !snapshot.rootMemoryId().equals(graph.currentRevision().memoryId())
                || !snapshot.rootPolicyId().equals(graph.policy().policyId())
                || !"MEMORY".equals(graph.policy().ownerKind())
                || !snapshot.rootMemoryId().equals(graph.policy().ownerId())
                || snapshot.rootPolicyRevisionNo() != graph.policy().currentRevisionNo()
                || !snapshot.rootPolicyId().equals(graph.policyRevision().policyId())
                || snapshot.rootPolicyRevisionNo() != graph.policyRevision().revisionNo()) {
            throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        }

        // Compute current canonical members and manifest
        List<DeletionPreviewPort.Member> currentMembers = CanonicalClosureComputer.buildMembers(graph);
        byte[] currentManifest = CanonicalClosureComputer.manifestHash(graph, currentMembers);

        // Verify manifest match
        if (!Arrays.equals(currentManifest, snapshot.manifestHash())) {
            throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        }

        // Verify member set exact match (count + each member)
        if (currentMembers.size() != snapshot.members().size()) {
            throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        }
        List<DeletionConfirmationPort.Member> snapshotOrdered = snapshot.members().stream()
                .sorted(Comparator.comparingLong(DeletionConfirmationPort.Member::ordinal))
                .toList();
        for (int i = 0; i < currentMembers.size(); i++) {
            DeletionPreviewPort.Member cm = currentMembers.get(i);
            DeletionConfirmationPort.Member sm = snapshotOrdered.get(i);
            if (!cm.memberKind().equals(sm.memberKind())
                    || !cm.targetId().equals(sm.targetId())
                    || !Objects.equals(cm.targetRevisionRef(), sm.targetRevisionRef())
                    || !cm.disposition().equals(sm.disposition())
                    || !Objects.equals(cm.sizeBytes(), sm.sizeBytes())
                    || !Arrays.equals(cm.contentHash(), sm.contentHash())) {
                throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
            }
        }
    }

    // -- helpers -------------------------------------------------------------

    private DeletionPreviewGraph readGraph(UUID memoryId) {
        DeletionPreviewGraph graph = previewPort.lockAndReadGraph(memoryId);
        if (graph == null) throw failure(CanonicalFailureCode.DELETION_PREVIEW_STALE);
        return graph;
    }

    private static void validateRequest(LocalV1S3B2BDeletionConfirmRequest request) {
        if (request == null
                || request.closureId() == null
                || request.previewRevision() < 1
                || request.manifestHash() == null
                || request.manifestHash().length != 32
                || request.actorId() == null
                || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("invalid S3B2B confirm request");
        }
    }

    private static LocalV1S3B2BException failure(CanonicalFailureCode code) {
        return new LocalV1S3B2BException(code);
    }
}
