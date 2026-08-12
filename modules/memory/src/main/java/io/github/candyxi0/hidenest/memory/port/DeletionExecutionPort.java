package io.github.candyxi0.hidenest.memory.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Database-neutral boundary for the S3C1A database erasure phase of a confirmed deletion closure. */
public interface DeletionExecutionPort {

    ExecutionResult executeDatabasePhase(UUID runId, UUID closureId, OffsetDateTime executedAt);

    DeletionRun findRunByClosureId(UUID closureId);

    List<PayloadTask> findPendingPayloadTasks(UUID runId);

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
}
