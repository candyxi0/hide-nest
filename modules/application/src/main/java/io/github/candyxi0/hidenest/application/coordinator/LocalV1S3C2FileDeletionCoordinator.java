package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1S3C2FileDeletionResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort.CompletionResult;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort.DeletionRun;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort.PayloadTask;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S3C2 application coordinator for file erasure and deletion closure.
 *
 * <p>Depends only on {@link DeletionExecutionPort} and {@link PayloadStore}.
 * Pre-checks all pending payload files via {@code head} before any delete,
 * then conditionally deletes, settles tasks, and completes the deletion run.
 */
public final class LocalV1S3C2FileDeletionCoordinator {

    private static final String NOT_FOUND = "NOT_FOUND";

    private final DeletionExecutionPort executionPort;
    private final PayloadStore payloadStore;
    private final Clock clock;

    public LocalV1S3C2FileDeletionCoordinator(
            DeletionExecutionPort executionPort,
            PayloadStore payloadStore,
            Clock clock) {
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort");
        this.payloadStore = Objects.requireNonNull(payloadStore, "payloadStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Execute the S3C2 file deletion phase for the given deletion run.
     *
     * <p>If the run is already {@code COMPLETED}, this is an exact replay:
     * zero {@link PayloadStore} calls, zero database writes.
     */
    public LocalV1S3C2FileDeletionResult execute(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        // 1. Read run — must exist
        DeletionRun run = executionPort.findRunById(runId);
        if (run == null) {
            throw new LocalV1S3C2Exception("DELETION_RUN_NOT_FOUND");
        }

        // 2. Already COMPLETED → exact replay, zero PayloadStore calls
        if ("COMPLETED".equals(run.state())) {
            return new LocalV1S3C2FileDeletionResult(
                    run.runId(), run.closureId(), run.state(),
                    run.payloadTaskCount(), run.completedAt());
        }

        // 3. Read pending tasks
        List<PayloadTask> pendingTasks = executionPort.findPendingPayloadTasks(runId);

        // 4. Zero-task run → complete immediately
        if (pendingTasks.isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            CompletionResult completed = executionPort.completeRun(runId, now);
            if (completed instanceof CompletionResult.Completed c) {
                return new LocalV1S3C2FileDeletionResult(
                        c.runId(), c.closureId(), c.state(), c.payloadTaskCount(), c.completedAt());
            }
            // Already completed (race)
            CompletionResult.AlreadyCompleted ac = (CompletionResult.AlreadyCompleted) completed;
            return new LocalV1S3C2FileDeletionResult(
                    ac.runId(), ac.closureId(), ac.state(), ac.payloadTaskCount(), ac.completedAt());
        }

        // 5. Pre-check phase: head all pending tasks
        List<TaskPlan> plans = new ArrayList<>(pendingTasks.size());
        for (PayloadTask task : pendingTasks) {
            try {
                PayloadHeadResult head = payloadStore.head(task.objectRef());
                // File exists — verify hash
                if (!Arrays.equals(head.contentHash(), task.expectedHash())) {
                    // Hash mismatch → fail the entire run
                    executionPort.recordFileFailure(runId, OffsetDateTime.now(clock));
                    throw new LocalV1S3C2Exception("DELETION_EXECUTION_FAILED");
                }
                plans.add(TaskPlan.forDeletion(task));
            } catch (PayloadStoreException e) {
                if (NOT_FOUND.equals(e.errorCode())) {
                    // File already gone (crash window) — idempotent settle
                    plans.add(TaskPlan.forIdempotentSettle(task));
                } else {
                    // Other store error → fail
                    executionPort.recordFileFailure(runId, OffsetDateTime.now(clock));
                    throw new LocalV1S3C2Exception("DELETION_EXECUTION_FAILED", e);
                }
            }
        }

        // 6. Delete phase: for each plan marked for deletion
        for (TaskPlan plan : plans) {
            if (plan.kind() == TaskPlanKind.DELETE) {
                PayloadTask task = plan.task();
                try {
                    payloadStore.delete(task.objectRef(), task.expectedHash());
                } catch (PayloadStoreException e) {
                    if (NOT_FOUND.equals(e.errorCode())) {
                        // Race or crash recovery — still allow idempotent settle
                    } else {
                        executionPort.recordFileFailure(runId, OffsetDateTime.now(clock));
                        throw new LocalV1S3C2Exception("DELETION_EXECUTION_FAILED", e);
                    }
                }
            }
        }

        // 7. Settle phase: mark each task as DELETED
        for (TaskPlan plan : plans) {
            PayloadTask task = plan.task();
            executionPort.settlePayloadTask(
                    task.deletionRunId(), task.payloadId(), task.objectRef(), task.expectedHash());
        }

        // 8. Complete the run
        OffsetDateTime now = OffsetDateTime.now(clock);
        CompletionResult completed = executionPort.completeRun(runId, now);
        if (completed instanceof CompletionResult.Completed c) {
            return new LocalV1S3C2FileDeletionResult(
                    c.runId(), c.closureId(), c.state(), c.payloadTaskCount(), c.completedAt());
        }
        // Already completed (concurrent execution)
        CompletionResult.AlreadyCompleted ac = (CompletionResult.AlreadyCompleted) completed;
        return new LocalV1S3C2FileDeletionResult(
                ac.runId(), ac.closureId(), ac.state(), ac.payloadTaskCount(), ac.completedAt());
    }

    // ── internal types ──────────────────────────────────────────────────

    private enum TaskPlanKind { DELETE, IDEMPOTENT_SETTLE }

    private record TaskPlan(TaskPlanKind kind, PayloadTask task) {
        static TaskPlan forDeletion(PayloadTask task) {
            return new TaskPlan(TaskPlanKind.DELETE, task);
        }
        static TaskPlan forIdempotentSettle(PayloadTask task) {
            return new TaskPlan(TaskPlanKind.IDEMPOTENT_SETTLE, task);
        }
    }
}
