package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1DeletionPreviewResult;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.memory.port.DeletionBindingPort;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Local V1 permanent-deletion HTTP facade.
 *
 * <p>This facade only does transport mapping, deterministic identity and phase orchestration. It
 * reuses the existing deletion core verbatim: {@link LocalV1S3ADeletionPreviewCoordinator} for
 * preview, {@link LocalV1S3B2BDeletionConfirmCoordinator} for confirmation/fencing,
 * {@link DeletionExecutionPort#executeDatabasePhase} for database erasure, and
 * {@link LocalV1S3C2FileDeletionCoordinator} for file erasure. Each phase commits independently so
 * a replay with the same idempotency key converges to the same run without a second fact.</p>
 */
public final class LocalV1DeletionWriteCoordinator {

    private static final String COMPLETED = "COMPLETED";
    private static final String FILE_PENDING = "FILE_PENDING";

    private final DeletionBindingPort bindingPort;
    private final DeletionPreviewPort previewPort;
    private final MemoryGovernancePort governancePort;
    private final LocalV1S3ADeletionPreviewCoordinator previewCoordinator;
    private final LocalV1S3B2BDeletionConfirmCoordinator confirmCoordinator;
    private final DeletionExecutionPort executionPort;
    private final LocalV1S3C2FileDeletionCoordinator fileDeletionCoordinator;
    private final Clock clock;

    public LocalV1DeletionWriteCoordinator(
            DeletionBindingPort bindingPort,
            DeletionPreviewPort previewPort,
            MemoryGovernancePort governancePort,
            LocalV1S3ADeletionPreviewCoordinator previewCoordinator,
            LocalV1S3B2BDeletionConfirmCoordinator confirmCoordinator,
            DeletionExecutionPort executionPort,
            LocalV1S3C2FileDeletionCoordinator fileDeletionCoordinator,
            Clock clock) {
        this.bindingPort = Objects.requireNonNull(bindingPort, "bindingPort");
        this.previewPort = Objects.requireNonNull(previewPort, "previewPort");
        this.governancePort = Objects.requireNonNull(governancePort, "governancePort");
        this.previewCoordinator = Objects.requireNonNull(previewCoordinator, "previewCoordinator");
        this.confirmCoordinator = Objects.requireNonNull(confirmCoordinator, "confirmCoordinator");
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort");
        this.fileDeletionCoordinator = Objects.requireNonNull(fileDeletionCoordinator, "fileDeletionCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── preview ────────────────────────────────────────────────────────────

    public LocalV1DeletionPreviewResult preview(
            UUID targetId,
            long expectedRevision,
            long expectedPolicyRevision,
            String requestManifestHashHex,
            String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (targetId == null || expectedRevision < 1 || expectedPolicyRevision < 1) {
            throw schema();
        }
        byte[] requestHash = LocalV1DeletionCanonicalizer.requestHash(targetId, expectedRevision, expectedPolicyRevision);
        if (!LocalV1DeletionCanonicalizer.constantTimeEqualsHex(requestManifestHashHex, requestHash)) {
            throw schema();
        }

        DeletionBindingPort.MemoryFacts facts = bindingPort.readMemoryFacts(targetId);
        if (facts == null) {
            throw new LocalV1DeletionException(LocalV1DeletionException.Code.NOT_FOUND);
        }
        if (facts.revisionNo() != expectedRevision || facts.policyRevisionNo() != expectedPolicyRevision) {
            throw new LocalV1DeletionException(LocalV1DeletionException.Code.DELETION_PREVIEW_STALE);
        }

        LocalV1S3ADeletionPreviewResult previewResult;
        try {
            previewResult = previewCoordinator.preview(new LocalV1S3ADeletionPreviewRequest(targetId, idempotencyKey, requestHash));
        } catch (LocalV1S3AException ex) {
            throw mapS3A(ex);
        }

        DeletionPreviewPort.ExistingPreview existing = previewPort.findByIdempotencyKey(idempotencyKey);
        if (existing == null) {
            throw new LocalV1DeletionException(LocalV1DeletionException.Code.INTERNAL_FAILURE);
        }
        return new LocalV1DeletionPreviewResult(
                existing.previewId(), existing.previewRevision(), existing.manifestHash(), existing.members(),
                previewResult.evidence(), previewResult.sharedMemories());
    }

    // ── confirm ────────────────────────────────────────────────────────────

    public LocalV1RunStatus confirm(
            UUID urlId,
            UUID targetId,
            long expectedRevision,
            long expectedPolicyRevision,
            String requestManifestHashHex,
            UUID previewId,
            long previewRevision,
            String manifestHashHex,
            String confirmIdempotencyKey) {
        requireIdempotencyKey(confirmIdempotencyKey);
        if (urlId == null || previewId == null || !urlId.equals(previewId)) {
            throw closureMismatch();
        }
        if (targetId == null || expectedRevision < 1 || expectedPolicyRevision < 1 || previewRevision < 1) {
            throw schema();
        }
        if (!LocalV1DeletionCanonicalizer.is64LowerHex(requestManifestHashHex)
                || !LocalV1DeletionCanonicalizer.is64LowerHex(manifestHashHex)) {
            throw schema();
        }

        DeletionBindingPort.ClosureBinding binding = bindingPort.readClosureBinding(urlId);
        if (binding == null) {
            throw closureMismatch();
        }
        byte[] requestHash = LocalV1DeletionCanonicalizer.requestHash(targetId, expectedRevision, expectedPolicyRevision);
        byte[] manifestHash = LocalV1DeletionCanonicalizer.hexToBytes(manifestHashHex);
        if (!LocalV1DeletionCanonicalizer.constantTimeEqualsHex(requestManifestHashHex, requestHash)) {
            throw schema();
        }
        if (!binding.rootMemoryId().equals(targetId)
                || binding.rootRevisionNo() != expectedRevision
                || binding.rootPolicyRevisionNo() != expectedPolicyRevision
                || !LocalV1DeletionCanonicalizer.constantTimeEquals(binding.requestHash(), requestHash)
                || binding.previewRevision() != previewRevision
                || !LocalV1DeletionCanonicalizer.constantTimeEquals(binding.manifestHash(), manifestHash)) {
            throw closureMismatch();
        }

        // Actor is derived from the root memory's current-revision perspective actor on first
        // confirm; on replay the memory may already be erased, so reuse the persisted decision.
        io.github.candyxi0.hidenest.memory.domain.Decision priorDecision =
                governancePort.findDecisionByIdempotencyKey(confirmIdempotencyKey);
        UUID actorId;
        if (priorDecision != null) {
            actorId = priorDecision.actorId();
        } else {
            DeletionBindingPort.MemoryFacts facts = bindingPort.readMemoryFacts(binding.rootMemoryId());
            if (facts == null || facts.perspectiveActorId() == null) {
                throw closureMismatch();
            }
            actorId = facts.perspectiveActorId();
        }
        if (governancePort.findActorRefById(actorId) == null) {
            throw closureMismatch();
        }

        UUID runId = deterministicRunId(binding.closureId(), confirmIdempotencyKey);

        try {
            confirmCoordinator.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                    binding.closureId(), binding.previewRevision(), binding.manifestHash(),
                    actorId, confirmIdempotencyKey));
        } catch (LocalV1S3B2BException ex) {
            throw mapS3B2B(ex);
        }

        if (executionPort.findRunByClosureId(binding.closureId()) == null) {
            try {
                executionPort.executeDatabasePhase(runId, binding.closureId(), OffsetDateTime.now(clock));
            } catch (RuntimeException ex) {
                throw new LocalV1DeletionException(LocalV1DeletionException.Code.INTERNAL_FAILURE, ex);
            }
        }

        try {
            fileDeletionCoordinator.execute(runId);
        } catch (LocalV1S3C2Exception ex) {
            // Database phase succeeded; a file-phase failure leaves a recoverable FILE_PENDING run.
            // The same run stays queryable and a replay of the same confirm resumes it.
        }

        return readRunStatus(runId);
    }

    // ── run status ──────────────────────────────────────────────────────────

    public LocalV1RunStatus findRunStatus(UUID runId) {
        if (runId == null) {
            return null;
        }
        DeletionExecutionPort.DeletionRun run = executionPort.findRunById(runId);
        return run == null ? null : mapRun(run);
    }

    private LocalV1RunStatus readRunStatus(UUID runId) {
        DeletionExecutionPort.DeletionRun run = executionPort.findRunById(runId);
        if (run == null) {
            throw new LocalV1DeletionException(LocalV1DeletionException.Code.INTERNAL_FAILURE);
        }
        return mapRun(run);
    }

    private static LocalV1RunStatus mapRun(DeletionExecutionPort.DeletionRun run) {
        if (COMPLETED.equals(run.state())) {
            return new LocalV1RunStatus(
                    run.runId(), "CANONICAL_COMMITTED", null, run.startedAt(), run.completedAt(), false);
        }
        // FILE_PENDING (the only other persisted state).
        String failureCode = run.lastFailureCode() == null ? null : run.lastFailureCode();
        return new LocalV1RunStatus(run.runId(), "DECISIONS_COMMITTED", failureCode, run.startedAt(), null, true);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static UUID deterministicRunId(UUID closureId, String confirmIdempotencyKey) {
        return UUID.nameUUIDFromBytes(
                ("deletion-run:" + closureId + ":" + confirmIdempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LocalV1DeletionException(LocalV1DeletionException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private static LocalV1DeletionException schema() {
        return new LocalV1DeletionException(LocalV1DeletionException.Code.REQUEST_SCHEMA_INVALID);
    }

    private static LocalV1DeletionException closureMismatch() {
        return new LocalV1DeletionException(LocalV1DeletionException.Code.DELETION_CLOSURE_MISMATCH);
    }

    private static LocalV1DeletionException mapS3A(LocalV1S3AException ex) {
        return switch (ex.code()) {
            case INVALID_ARGUMENT -> schema();
            case NOT_FOUND -> new LocalV1DeletionException(LocalV1DeletionException.Code.NOT_FOUND);
            case DELETION_FENCED -> new LocalV1DeletionException(LocalV1DeletionException.Code.DELETION_FENCED);
            case IDEMPOTENCY_CONFLICT -> new LocalV1DeletionException(LocalV1DeletionException.Code.IDEMPOTENCY_KEY_REUSED);
            case PREVIEW_STALE -> new LocalV1DeletionException(LocalV1DeletionException.Code.DELETION_PREVIEW_STALE);
            default -> new LocalV1DeletionException(LocalV1DeletionException.Code.INTERNAL_FAILURE, ex);
        };
    }

    private static LocalV1DeletionException mapS3B2B(LocalV1S3B2BException ex) {
        return switch (ex.failureCode()) {
            case DELETION_CLOSURE_MISMATCH -> closureMismatch();
            case DELETION_PREVIEW_STALE ->
                    new LocalV1DeletionException(LocalV1DeletionException.Code.DELETION_PREVIEW_STALE);
            default -> new LocalV1DeletionException(LocalV1DeletionException.Code.INTERNAL_FAILURE, ex);
        };
    }
}
