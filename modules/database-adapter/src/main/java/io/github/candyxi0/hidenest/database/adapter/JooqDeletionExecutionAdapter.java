package io.github.candyxi0.hidenest.database.adapter;

import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/** PostgreSQL/jOOQ implementation of the deletion execution boundary (S3C1A + S3C2). */
public final class JooqDeletionExecutionAdapter implements DeletionExecutionPort {

    private static final String EXECUTE_SQL =
            "SELECT * FROM runtime.execute_confirmed_deletion_database_phase(?::uuid, ?::uuid, ?::timestamptz)";
    private static final String SETTLE_SQL =
            "SELECT * FROM runtime.settle_deletion_payload_task(?::uuid, ?::uuid, ?::text, ?::bytea)";
    private static final String FAILURE_SQL =
            "SELECT * FROM runtime.record_deletion_file_failure(?::uuid, ?::timestamptz)";
    private static final String COMPLETE_SQL =
            "SELECT * FROM runtime.complete_deletion_run(?::uuid, ?::timestamptz)";

    private final DSLContext dsl;

    public JooqDeletionExecutionAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    // ── S3C1A database erasure ────────────────────────────────────────────

    @Override
    public ExecutionResult executeDatabasePhase(UUID runId, UUID closureId, OffsetDateTime executedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(executedAt, "executedAt");

        var row = dsl.resultQuery(EXECUTE_SQL, runId, closureId, executedAt).fetchOne();
        if (row == null) {
            throw new IllegalStateException("execute_confirmed_deletion_database_phase returned no row");
        }
        return new ExecutionResult(
                row.get("o_run_id", UUID.class),
                row.get("o_closure_id", UUID.class),
                row.get("o_state", String.class),
                row.get("o_payload_task_count", Long.class),
                row.get("o_database_erased_at", OffsetDateTime.class));
    }

    @Override
    public DeletionRun findRunByClosureId(UUID closureId) {
        Objects.requireNonNull(closureId, "closureId");
        var row = dsl.resultQuery(
                "SELECT deletion_run_id, closure_id, confirmed_by_decision_id,"
                        + " state, started_at, database_erased_at, completed_at,"
                        + " last_failure_code, payload_task_count"
                        + " FROM runtime.deletion_run WHERE closure_id = ?",
                closureId).fetchOne();
        if (row == null) return null;
        return new DeletionRun(
                row.get("deletion_run_id", UUID.class),
                row.get("closure_id", UUID.class),
                row.get("confirmed_by_decision_id", UUID.class),
                row.get("state", String.class),
                row.get("started_at", OffsetDateTime.class),
                row.get("database_erased_at", OffsetDateTime.class),
                row.get("completed_at", OffsetDateTime.class),
                row.get("last_failure_code", String.class),
                row.get("payload_task_count", Long.class));
    }

    @Override
    public DeletionRun findRunById(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        var row = dsl.resultQuery(
                "SELECT deletion_run_id, closure_id, confirmed_by_decision_id,"
                        + " state, started_at, database_erased_at, completed_at,"
                        + " last_failure_code, payload_task_count"
                        + " FROM runtime.deletion_run WHERE deletion_run_id = ?",
                runId).fetchOne();
        if (row == null) return null;
        return new DeletionRun(
                row.get("deletion_run_id", UUID.class),
                row.get("closure_id", UUID.class),
                row.get("confirmed_by_decision_id", UUID.class),
                row.get("state", String.class),
                row.get("started_at", OffsetDateTime.class),
                row.get("database_erased_at", OffsetDateTime.class),
                row.get("completed_at", OffsetDateTime.class),
                row.get("last_failure_code", String.class),
                row.get("payload_task_count", Long.class));
    }

    @Override
    public List<PayloadTask> findPendingPayloadTasks(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        var rows = dsl.resultQuery(
                "SELECT deletion_run_id, payload_id, object_ref, expected_hash, state, created_at"
                        + " FROM runtime.deletion_payload_task"
                        + " WHERE deletion_run_id = ? AND state = 'PENDING'"
                        + " ORDER BY payload_id",
                runId).fetch();
        List<PayloadTask> tasks = new ArrayList<>(rows.size());
        for (var row : rows) {
            tasks.add(new PayloadTask(
                    row.get("deletion_run_id", UUID.class),
                    row.get("payload_id", UUID.class),
                    row.get("object_ref", String.class),
                    row.get("expected_hash", byte[].class),
                    row.get("state", String.class),
                    row.get("created_at", OffsetDateTime.class)));
        }
        return tasks;
    }

    // ── S3C2 file settlement ──────────────────────────────────────────────

    @Override
    public SettlementResult settlePayloadTask(UUID runId, UUID payloadId, String objectRef, byte[] expectedHash) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(payloadId, "payloadId");
        Objects.requireNonNull(objectRef, "objectRef");
        Objects.requireNonNull(expectedHash, "expectedHash");

        var row = dsl.resultQuery(SETTLE_SQL, runId, payloadId, objectRef, expectedHash).fetchOne();
        if (row == null) {
            throw new IllegalStateException("settle_deletion_payload_task returned no row");
        }
        UUID oRunId = row.get("o_run_id", UUID.class);
        UUID oPayloadId = row.get("o_payload_id", UUID.class);
        String oState = row.get("o_state", String.class);
        boolean oSettled = row.get("o_settled", Boolean.class);

        if (oSettled) {
            return new SettlementResult.Settled(oRunId, oPayloadId, oState);
        }
        return new SettlementResult.AlreadySettled(oRunId, oPayloadId, oState);
    }

    @Override
    public void recordFileFailure(UUID runId, OffsetDateTime failedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(failedAt, "failedAt");

        var row = dsl.resultQuery(FAILURE_SQL, runId, failedAt).fetchOne();
        if (row == null) {
            throw new IllegalStateException("record_deletion_file_failure returned no row");
        }
    }

    @Override
    public CompletionResult completeRun(UUID runId, OffsetDateTime completedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(completedAt, "completedAt");

        var row = dsl.resultQuery(COMPLETE_SQL, runId, completedAt).fetchOne();
        if (row == null) {
            throw new IllegalStateException("complete_deletion_run returned no row");
        }
        UUID oRunId = row.get("o_run_id", UUID.class);
        UUID oClosureId = row.get("o_closure_id", UUID.class);
        String oState = row.get("o_state", String.class);
        long oPayloadTaskCount = row.get("o_payload_task_count", Long.class);
        OffsetDateTime oCompletedAt = row.get("o_completed_at", OffsetDateTime.class);
        boolean oCompleted = row.get("o_completed", Boolean.class);

        if (oCompleted) {
            return new CompletionResult.Completed(oRunId, oClosureId, oState, oPayloadTaskCount, oCompletedAt);
        }
        return new CompletionResult.AlreadyCompleted(oRunId, oClosureId, oState, oPayloadTaskCount, oCompletedAt);
    }
}
