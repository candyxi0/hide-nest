package io.github.candyxi0.hidenest.memory.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Database-neutral boundary for the deletion execution phases of a confirmed deletion closure. */
public interface DeletionExecutionPort {

    // ── S3C1A database erasure ────────────────────────────────────────────

    ExecutionResult executeDatabasePhase(UUID runId, UUID closureId, OffsetDateTime executedAt);

    DeletionRun findRunByClosureId(UUID closureId);

    DeletionRun findRunById(UUID runId);

    List<PayloadTask> findPendingPayloadTasks(UUID runId);

    // ── S3C2 file settlement ──────────────────────────────────────────────

    SettlementResult settlePayloadTask(UUID runId, UUID payloadId, String objectRef, byte[] expectedHash);

    void recordFileFailure(UUID runId, OffsetDateTime failedAt);

    CompletionResult completeRun(UUID runId, OffsetDateTime completedAt);

    // ── Result types ──────────────────────────────────────────────────────

    record ExecutionResult(
            UUID runId,
            UUID closureId,
            String state,
            long payloadTaskCount,
            OffsetDateTime databaseErasedAt) {
        public ExecutionResult {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(closureId, "closureId");
            Objects.requireNonNull(state, "state");
            if (state.isBlank()) throw new IllegalArgumentException("state must not be blank");
            if (payloadTaskCount < 0) throw new IllegalArgumentException("payloadTaskCount must be non-negative");
            Objects.requireNonNull(databaseErasedAt, "databaseErasedAt");
        }
    }

    record DeletionRun(
            UUID runId,
            UUID closureId,
            UUID confirmedByDecisionId,
            String state,
            OffsetDateTime startedAt,
            OffsetDateTime databaseErasedAt,
            OffsetDateTime completedAt,
            String lastFailureCode,
            long payloadTaskCount) {
        public DeletionRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(closureId, "closureId");
            Objects.requireNonNull(confirmedByDecisionId, "confirmedByDecisionId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(databaseErasedAt, "databaseErasedAt");
            if (payloadTaskCount < 0) throw new IllegalArgumentException("payloadTaskCount must be non-negative");
        }
    }

    record PayloadTask(
            UUID deletionRunId,
            UUID payloadId,
            String objectRef,
            byte[] expectedHash,
            String state,
            OffsetDateTime createdAt) {
        public PayloadTask {
            Objects.requireNonNull(deletionRunId, "deletionRunId");
            Objects.requireNonNull(payloadId, "payloadId");
            Objects.requireNonNull(objectRef, "objectRef");
            if (objectRef.isBlank()) throw new IllegalArgumentException("objectRef must not be blank");
            expectedHash = expectedHash == null ? null : expectedHash.clone();
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public byte[] expectedHash() {
            return expectedHash == null ? null : expectedHash.clone();
        }
    }

    sealed interface SettlementResult {
        UUID runId();
        UUID payloadId();
        String state();

        record Settled(UUID runId, UUID payloadId, String state) implements SettlementResult {
            public Settled {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(payloadId, "payloadId");
                Objects.requireNonNull(state, "state");
            }
        }

        record AlreadySettled(UUID runId, UUID payloadId, String state) implements SettlementResult {
            public AlreadySettled {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(payloadId, "payloadId");
                Objects.requireNonNull(state, "state");
            }
        }
    }

    sealed interface CompletionResult {
        UUID runId();
        UUID closureId();
        String state();
        long payloadTaskCount();
        OffsetDateTime completedAt();

        record Completed(UUID runId, UUID closureId, String state,
                         long payloadTaskCount, OffsetDateTime completedAt) implements CompletionResult {
            public Completed {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(closureId, "closureId");
                Objects.requireNonNull(state, "state");
                if (payloadTaskCount < 0) throw new IllegalArgumentException("payloadTaskCount must be non-negative");
                Objects.requireNonNull(completedAt, "completedAt");
            }
        }

        record AlreadyCompleted(UUID runId, UUID closureId, String state,
                                long payloadTaskCount, OffsetDateTime completedAt) implements CompletionResult {
            public AlreadyCompleted {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(closureId, "closureId");
                Objects.requireNonNull(state, "state");
                if (payloadTaskCount < 0) throw new IllegalArgumentException("payloadTaskCount must be non-negative");
                Objects.requireNonNull(completedAt, "completedAt");
            }
        }
    }
}
