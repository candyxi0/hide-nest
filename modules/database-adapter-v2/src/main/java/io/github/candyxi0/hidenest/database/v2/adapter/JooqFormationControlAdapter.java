package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.runtime.domain.FormationAttempt;
import io.github.candyxi0.hidenest.runtime.domain.FormationAttentionNotice;
import io.github.candyxi0.hidenest.runtime.domain.FormationResultKind;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementOutcome;
import io.github.candyxi0.hidenest.runtime.domain.SourceBoundary;
import io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding;
import io.github.candyxi0.hidenest.runtime.port.CanonicalCommitPort;
import io.github.candyxi0.hidenest.runtime.port.FormationControlPort;
import io.github.candyxi0.hidenest.runtime.port.FormationHardCheckPort;
import io.github.candyxi0.hidenest.runtime.port.FormationStopPort;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Database-owned Formation claim and one-slot settlement. */
public final class JooqFormationControlAdapter implements FormationControlPort {
    private static final String NOTICE = "有一项连续性任务暂未完成，后续任务正在等待，请联系指挥官排查";

    private final DSLContext dsl;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryDelay;

    public JooqFormationControlAdapter(DSLContext dsl, Clock clock, int maxAttempts, Duration retryDelay) {
        this.dsl = Objects.requireNonNull(dsl);
        this.clock = Objects.requireNonNull(clock);
        if (maxAttempts < 1 || retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Override
    public Optional<FormationAttempt> claim(String ownerRef, Duration lease) {
        if (ownerRef == null || ownerRef.isBlank() || ownerRef.length() > 128 || lease == null || !lease.isPositive()) {
            throw new IllegalArgumentException("invalid owner or lease");
        }
        OffsetDateTime now = now();
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record task = tx.fetchOne(
                    "SELECT t.* FROM runtime.formation_task t JOIN runtime.source_progress p ON p.source_id=t.source_id "
                            + "WHERE t.state IN ('PENDING','RETRY_WAIT','ATTEMPTING') AND t.ready_at<=?::timestamptz "
                            + "AND t.to_sequence<=p.stable_sequence AND p.stable_sequence=p.discovered_sequence "
                            + "AND p.stable_cursor=p.discovered_cursor "
                            + "AND p.stable_source_version=p.discovered_source_version "
                            + "AND (t.predecessor_task_id IS NULL OR EXISTS (SELECT 1 FROM runtime.formation_task pred "
                            + "WHERE pred.task_id=t.predecessor_task_id AND pred.state IN ('COMMITTED_WRITE','COMMITTED_NO_CHANGE'))) "
                            + "AND p.read_kind<>'UNAVAILABLE' "
                            + "AND (t.read_kind='STABLE_REREAD' OR (t.read_kind='BOUNDED_SNAPSHOT' "
                            + "AND t.read_expires_at>?::timestamptz)) "
                            + "AND NOT EXISTS (SELECT 1 FROM runtime.formation_attempt a WHERE a.task_id=t.task_id "
                            + "AND a.state='RUNNING' AND a.lease_until>?::timestamptz) "
                            + "ORDER BY t.ready_at,t.task_id LIMIT 1 FOR UPDATE OF t SKIP LOCKED",
                    now,
                    now,
                    now);
            if (task == null) {
                return Optional.<FormationAttempt>empty();
            }
            UUID taskId = task.get("task_id", UUID.class);
            long generation = task.get("generation", Long.class);
            if (generation >= maxAttempts) {
                attention(tx, taskId, now);
                return Optional.<FormationAttempt>empty();
            }
            UUID attemptId = UUID.randomUUID();
            OffsetDateTime leaseUntil = now.plus(lease);
            tx.execute(
                    "UPDATE runtime.formation_task SET state='ATTEMPTING',generation=?,updated_at=?::timestamptz "
                            + "WHERE task_id=?::uuid",
                    generation + 1,
                    now,
                    taskId);
            tx.execute(
                    "INSERT INTO runtime.formation_attempt "
                            + "(attempt_id,task_id,generation,owner_ref,lease_until,started_at,state) "
                            + "VALUES (?::uuid,?::uuid,?,?,?::timestamptz,?::timestamptz,'RUNNING')",
                    attemptId,
                    taskId,
                    generation + 1,
                    ownerRef,
                    leaseUntil,
                    now);
            return Optional.of(new FormationAttempt(
                    attemptId,
                    taskId,
                    task.get("source_id", UUID.class),
                    generation + 1,
                    boundaryOrNull(task, "from"),
                    boundary(task, "to"),
                    binding(task),
                    leaseUntil));
        });
    }

    @Override
    public boolean renew(UUID attemptId, String ownerRef, Duration lease) {
        if (attemptId == null || ownerRef == null || lease == null || !lease.isPositive()) {
            throw new IllegalArgumentException("invalid renewal");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record attempt =
                    tx.fetchOne("SELECT task_id FROM runtime.formation_attempt WHERE attempt_id=?::uuid", attemptId);
            if (attempt == null) {
                return false;
            }
            Record task = tx.fetchOne(
                    "SELECT state,winner_attempt_id FROM runtime.formation_task WHERE task_id=?::uuid FOR UPDATE",
                    attempt.get("task_id", UUID.class));
            if (!"ATTEMPTING".equals(task.get("state", String.class))
                    || task.get("winner_attempt_id", UUID.class) != null) {
                return false;
            }
            OffsetDateTime now = now();
            return tx.execute(
                            "UPDATE runtime.formation_attempt SET lease_until=?::timestamptz "
                                    + "WHERE attempt_id=?::uuid AND owner_ref=? AND state='RUNNING' "
                                    + "AND lease_until>?::timestamptz",
                            now.plus(lease),
                            attemptId,
                            ownerRef,
                            now)
                    == 1;
        });
    }

    @Override
    public FormationSettlementOutcome settle(
            FormationSettlementCandidate candidate,
            FormationHardCheckPort hardChecks,
            CanonicalCommitPort canonicalCommit,
            FormationStopPort stopPort) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(hardChecks);
        Objects.requireNonNull(canonicalCommit);
        Objects.requireNonNull(stopPort);
        OffsetDateTime now = now();
        Settlement settled;
        try {
            settled = dsl.transactionResult(configuration -> {
                DSLContext tx = configuration.dsl();
                Record task = tx.fetchOne(
                        "SELECT * FROM runtime.formation_task WHERE task_id=?::uuid FOR UPDATE", candidate.taskId());
                if (task == null) {
                    return new Settlement(FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED, List.of());
                }
                Record attempt = tx.fetchOne(
                        "SELECT * FROM runtime.formation_attempt WHERE attempt_id=?::uuid AND task_id=?::uuid",
                        candidate.attemptId(),
                        candidate.taskId());
                if (attempt == null) {
                    return new Settlement(FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED, List.of());
                }
                if (task.get("winner_attempt_id", UUID.class) != null) {
                    if (candidate.attemptId().equals(task.get("winner_attempt_id", UUID.class))) {
                        boolean same = Objects.equals(
                                        attempt.get("idempotency_key", UUID.class), candidate.idempotencyKey())
                                && candidate.kind().name().equals(attempt.get("result_kind", String.class))
                                && Arrays.equals(attempt.get("result_hash", byte[].class), candidate.resultHash());
                        return new Settlement(
                                same
                                        ? FormationSettlementOutcome.IDEMPOTENT_REPLAY
                                        : FormationSettlementOutcome.IDEMPOTENCY_CONFLICT,
                                List.of());
                    }
                    return new Settlement(FormationSettlementOutcome.SLOT_ALREADY_SETTLED, List.of());
                }
                if (!"RUNNING".equals(attempt.get("state", String.class))) {
                    return new Settlement(FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED, List.of());
                }
                boolean validEnvelope = candidate.complete()
                        && candidate.schemaVersion() == 1
                        && candidate.sourceId().equals(task.get("source_id", UUID.class))
                        && Objects.equals(candidate.fromExclusive(), boundaryOrNull(task, "from"))
                        && candidate.toInclusive().equals(boundary(task, "to"))
                        && candidate.readBinding().equals(binding(task))
                        && (candidate.kind() == FormationResultKind.WRITE_SET
                                || candidate.kind() == FormationResultKind.NO_LONG_TERM_CHANGE);
                if (!validEnvelope) {
                    return new Settlement(FormationSettlementOutcome.INVALID_RESULT, List.of());
                }
                boolean valid = tx.connectionResult(connection -> hardChecks.valid(connection, candidate));
                if (!valid) {
                    return new Settlement(FormationSettlementOutcome.INVALID_RESULT, List.of());
                }
                if (candidate.kind() == FormationResultKind.WRITE_SET) {
                    try {
                        tx.connection(connection -> canonicalCommit.commit(connection, candidate));
                    } catch (RuntimeException failure) {
                        throw new CanonicalCommitFailure(failure);
                    }
                }
                tx.execute(
                        "UPDATE runtime.formation_attempt SET state='WON',result_kind=?,result_hash=?,"
                                + "idempotency_key=?::uuid,finished_at=?::timestamptz WHERE attempt_id=?::uuid",
                        candidate.kind().name(),
                        candidate.resultHash(),
                        candidate.idempotencyKey(),
                        now,
                        candidate.attemptId());
                String state =
                        candidate.kind() == FormationResultKind.WRITE_SET ? "COMMITTED_WRITE" : "COMMITTED_NO_CHANGE";
                tx.execute(
                        "UPDATE runtime.formation_task SET state=?,winner_attempt_id=?::uuid,settled_at=?::timestamptz,"
                                + "updated_at=?::timestamptz WHERE task_id=?::uuid",
                        state,
                        candidate.attemptId(),
                        now,
                        now,
                        candidate.taskId());
                int advanced = tx.execute(
                        "UPDATE runtime.source_progress SET processed_sequence=?,processed_cursor=?,"
                                + "processed_source_version=?,updated_at=?::timestamptz WHERE source_id=?::uuid "
                                + "AND processed_sequence IS NOT DISTINCT FROM ?::bigint "
                                + "AND processed_cursor IS NOT DISTINCT FROM ?::text "
                                + "AND processed_source_version IS NOT DISTINCT FROM ?::text",
                        candidate.toInclusive().sequence(),
                        candidate.toInclusive().cursor(),
                        candidate.toInclusive().sourceVersion(),
                        now,
                        candidate.sourceId(),
                        candidate.fromExclusive() == null
                                ? null
                                : candidate.fromExclusive().sequence(),
                        candidate.fromExclusive() == null
                                ? null
                                : candidate.fromExclusive().cursor(),
                        candidate.fromExclusive() == null
                                ? null
                                : candidate.fromExclusive().sourceVersion());
                if (advanced != 1) {
                    throw new SettlementFenceFailure();
                }
                List<UUID> siblings = tx.fetch(
                                "SELECT attempt_id FROM runtime.formation_attempt WHERE task_id=?::uuid "
                                        + "AND attempt_id<>?::uuid AND state='RUNNING'",
                                candidate.taskId(),
                                candidate.attemptId())
                        .getValues("attempt_id", UUID.class);
                tx.execute(
                        "UPDATE runtime.formation_attempt SET state='STOP_REQUESTED' WHERE task_id=?::uuid "
                                + "AND attempt_id<>?::uuid AND state='RUNNING'",
                        candidate.taskId(),
                        candidate.attemptId());
                return new Settlement(
                        candidate.kind() == FormationResultKind.WRITE_SET
                                ? FormationSettlementOutcome.COMMITTED_WRITE
                                : FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                        siblings);
            });
        } catch (CanonicalCommitFailure failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if ("CURRENT_CONFLICT".equals(cause.getMessage())) {
                    fail(candidate.attemptId(), "CURRENT_CONFLICT");
                    return FormationSettlementOutcome.CURRENT_CONFLICT;
                }
                if ("REVISE_TARGET_INVALID".equals(cause.getMessage())
                        || "SUPERSEDE_TARGET_INVALID".equals(cause.getMessage())) {
                    fail(candidate.attemptId(), cause.getMessage());
                    return FormationSettlementOutcome.INVALID_RESULT;
                }
            }
            return fail(candidate.attemptId(), "CANONICAL_COMMIT_FAILED");
        } catch (SettlementFenceFailure failure) {
            return fail(candidate.attemptId(), "PROCESSED_FENCE_CONFLICT");
        }
        for (UUID sibling : settled.siblings()) {
            try {
                stopPort.requestStop(sibling);
            } catch (RuntimeException ignored) {
                // The persisted settlement slot rejects any result from this sibling.
            }
        }
        if (settled.outcome() == FormationSettlementOutcome.INVALID_RESULT) {
            fail(candidate.attemptId(), "INVALID_RESULT");
        }
        return settled.outcome();
    }

    @Override
    public FormationSettlementOutcome fail(UUID attemptId, String failureCode) {
        if (attemptId == null || failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid attempt or failure code");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record attemptRef =
                    tx.fetchOne("SELECT task_id FROM runtime.formation_attempt WHERE attempt_id=?::uuid", attemptId);
            if (attemptRef == null) {
                return FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED;
            }
            UUID taskId = attemptRef.get("task_id", UUID.class);
            Record task = tx.fetchOne("SELECT * FROM runtime.formation_task WHERE task_id=?::uuid FOR UPDATE", taskId);
            if (task.get("winner_attempt_id", UUID.class) != null) {
                return FormationSettlementOutcome.SLOT_ALREADY_SETTLED;
            }
            OffsetDateTime now = now();
            int changed = tx.execute(
                    "UPDATE runtime.formation_attempt SET state='FAILED',failure_code=?,finished_at=?::timestamptz "
                            + "WHERE attempt_id=?::uuid AND state='RUNNING'",
                    failureCode,
                    now,
                    attemptId);
            if (changed == 0) {
                return FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED;
            }
            boolean leasedSibling = tx.fetchOne(
                            "SELECT 1 FROM runtime.formation_attempt WHERE task_id=?::uuid "
                                    + "AND state='RUNNING' AND lease_until>?::timestamptz LIMIT 1",
                            taskId,
                            now)
                    != null;
            if (leasedSibling) {
                // This attempt has failed, but the task remains ATTEMPTING for its live sibling.
                return FormationSettlementOutcome.RETRY_WAIT;
            }
            if (task.get("generation", Long.class) >= maxAttempts) {
                attention(tx, taskId, now);
                return FormationSettlementOutcome.ATTENTION_REQUIRED;
            }
            tx.execute(
                    "UPDATE runtime.formation_task SET state='RETRY_WAIT',ready_at=?::timestamptz,"
                            + "updated_at=?::timestamptz WHERE task_id=?::uuid",
                    now.plus(retryDelay),
                    now,
                    taskId);
            return FormationSettlementOutcome.RETRY_WAIT;
        });
    }

    @Override
    public List<FormationAttentionNotice> attentionNotices(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        List<FormationAttentionNotice> notices = new ArrayList<>();
        for (Record row : dsl.fetch(
                "SELECT n.task_id,n.created_at FROM runtime.formation_attention_notice n "
                        + "JOIN runtime.formation_task t ON t.task_id=n.task_id "
                        + "WHERE t.state='ATTENTION_REQUIRED' ORDER BY n.created_at,n.task_id LIMIT ?",
                limit)) {
            notices.add(new FormationAttentionNotice(
                    row.get("task_id", UUID.class), row.get("created_at", OffsetDateTime.class), NOTICE));
        }
        return List.copyOf(notices);
    }

    private static void attention(DSLContext tx, UUID taskId, OffsetDateTime now) {
        tx.execute(
                "UPDATE runtime.formation_task SET state='ATTENTION_REQUIRED',updated_at=?::timestamptz "
                        + "WHERE task_id=?::uuid AND winner_attempt_id IS NULL",
                now,
                taskId);
        tx.execute(
                "INSERT INTO runtime.formation_attention_notice(task_id,created_at) VALUES (?::uuid,?::timestamptz) "
                        + "ON CONFLICT (task_id) DO NOTHING",
                taskId,
                now);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static SourceBoundary boundary(Record row, String prefix) {
        return new SourceBoundary(
                row.get(prefix + "_sequence", Long.class),
                row.get(prefix + "_cursor", String.class),
                row.get(prefix + "_source_version", String.class));
    }

    private static SourceBoundary boundaryOrNull(Record row, String prefix) {
        return row.get(prefix + "_sequence", Long.class) == null ? null : boundary(row, prefix);
    }

    private static SourceReadBinding binding(Record row) {
        return new SourceReadBinding(
                SourceReadBinding.Kind.valueOf(row.get("read_kind", String.class)),
                row.get("read_ref", String.class),
                row.get("read_version", String.class),
                row.get("read_expires_at", OffsetDateTime.class));
    }

    private record Settlement(FormationSettlementOutcome outcome, List<UUID> siblings) {}

    private static final class CanonicalCommitFailure extends RuntimeException {
        private CanonicalCommitFailure(RuntimeException cause) {
            super(cause);
        }
    }

    private static final class SettlementFenceFailure extends RuntimeException {}
}
