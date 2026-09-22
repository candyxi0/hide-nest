package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.runtime.domain.FormationPendingTask;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvance;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceOutcome;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceResult;
import io.github.candyxi0.hidenest.runtime.domain.SourceBoundary;
import io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding;
import io.github.candyxi0.hidenest.runtime.port.FormationIntakePort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** PostgreSQL implementation of the body-free S02-A Formation intake seam. */
public final class JooqFormationIntakeAdapter implements FormationIntakePort {

    private static final long SOURCE_LOCK_SEED = 230_023L;

    private final DSLContext dsl;
    private final Clock clock;

    public JooqFormationIntakeAdapter(DSLContext dsl) {
        this(dsl, Clock.systemUTC());
    }

    public JooqFormationIntakeAdapter(DSLContext dsl, Clock clock) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SourceAdvanceResult recordSourceAdvanced(SourceAdvance advance) {
        Objects.requireNonNull(advance, "advance must not be null");
        byte[] requestHash = digest(advance);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);

        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            lockSource(tx, advance.sourceId());
            requireRegisteredSource(tx, advance.sourceId());

            Record progress = fetchProgress(tx, advance.sourceId());
            if (progress == null) {
                insertProgress(tx, advance, requestHash, now);
                UUID taskId = upsertPending(
                        tx,
                        advance.sourceId(),
                        null,
                        advance.stable(),
                        advance.readBinding(),
                        now,
                        now.plusMinutes(90));
                return new SourceAdvanceResult(
                        SourceAdvanceOutcome.CREATED, taskId, taskId == null ? null : now.plusMinutes(90));
            }

            long currentNotification = progress.get("last_notification_sequence", Long.class);
            byte[] currentHash = progress.get("last_notification_hash", byte[].class);
            UUID currentTaskId = findPendingTaskId(tx, advance.sourceId());
            OffsetDateTime currentReadyAt = findPendingReadyAt(tx, advance.sourceId());

            if (advance.notificationSequence() < currentNotification) {
                return new SourceAdvanceResult(SourceAdvanceOutcome.STALE_NOOP, currentTaskId, currentReadyAt);
            }
            if (advance.notificationSequence() == currentNotification) {
                SourceAdvanceOutcome outcome = Arrays.equals(requestHash, currentHash)
                        ? SourceAdvanceOutcome.EXACT_REPLAY
                        : SourceAdvanceOutcome.CONFLICT;
                return new SourceAdvanceResult(outcome, currentTaskId, currentReadyAt);
            }

            SourceBoundary currentDiscovered = boundary(progress, "discovered");
            SourceBoundary currentStable = nullableBoundary(progress, "stable");
            SourceBoundary currentProcessed = nullableBoundary(progress, "processed");
            if (regresses(currentDiscovered, advance.discovered())
                    || regressesNullable(currentStable, advance.stable())) {
                return new SourceAdvanceResult(
                        SourceAdvanceOutcome.SOURCE_ORDER_INVALID, currentTaskId, currentReadyAt);
            }

            SourceReadBinding currentBinding = readBinding(progress);
            boolean sourceChanged =
                    !currentDiscovered.equals(advance.discovered()) || !Objects.equals(currentStable, advance.stable());
            OffsetDateTime lastRealChange = progress.get("last_real_change_at", OffsetDateTime.class);
            OffsetDateTime quietUntil = progress.get("quiet_until", OffsetDateTime.class);
            if (sourceChanged) {
                lastRealChange = now;
                quietUntil = now.plusMinutes(90);
            }

            updateProgress(tx, advance, requestHash, currentProcessed, lastRealChange, quietUntil, now);
            UUID taskId = upsertPending(
                    tx, advance.sourceId(), currentProcessed, advance.stable(), advance.readBinding(), now, quietUntil);

            SourceAdvanceOutcome outcome =
                    sourceChanged ? SourceAdvanceOutcome.MERGED : SourceAdvanceOutcome.HEARTBEAT_NOOP;
            if (!sourceChanged && !currentBinding.equals(advance.readBinding())) {
                outcome = SourceAdvanceOutcome.HEARTBEAT_NOOP;
            }
            return new SourceAdvanceResult(outcome, taskId, taskId == null ? null : quietUntil);
        });
    }

    @Override
    public List<FormationPendingTask> findReadyFormationTasks(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        OffsetDateTime asOf = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var rows = dsl.fetch(
                "SELECT t.task_id, t.source_id, t.from_sequence, t.from_cursor, t.from_source_version, "
                        + "t.to_sequence, t.to_cursor, t.to_source_version, t.read_kind, t.read_ref, "
                        + "t.read_version, t.read_expires_at, t.ready_at, t.generation, t.created_at, t.updated_at "
                        + "FROM runtime.formation_task t JOIN runtime.source_progress p ON p.source_id=t.source_id "
                        + "WHERE t.state='PENDING' AND t.ready_at <= ?::timestamptz "
                        + "AND t.to_sequence=p.stable_sequence AND p.stable_sequence=p.discovered_sequence "
                        + "AND (t.read_kind='STABLE_REREAD' "
                        + "OR (t.read_kind='BOUNDED_SNAPSHOT' AND t.read_expires_at > ?::timestamptz)) "
                        + "ORDER BY t.ready_at, t.task_id LIMIT ?",
                asOf,
                asOf,
                limit);
        List<FormationPendingTask> tasks = new ArrayList<>(rows.size());
        for (Record row : rows) {
            tasks.add(new FormationPendingTask(
                    row.get("task_id", UUID.class),
                    row.get("source_id", UUID.class),
                    nullableBoundary(row, "from"),
                    boundary(row, "to"),
                    readBinding(row),
                    row.get("ready_at", OffsetDateTime.class),
                    row.get("generation", Long.class),
                    row.get("created_at", OffsetDateTime.class),
                    row.get("updated_at", OffsetDateTime.class)));
        }
        return List.copyOf(tasks);
    }

    private static void lockSource(DSLContext tx, UUID sourceId) {
        tx.fetch(
                "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?::text, ?))",
                sourceId,
                SOURCE_LOCK_SEED);
    }

    private static void requireRegisteredSource(DSLContext tx, UUID sourceId) {
        if (tx.fetchOne("SELECT source_id FROM runtime.source_registration WHERE source_id=?::uuid", sourceId)
                == null) {
            throw new IllegalArgumentException("source is not registered");
        }
    }

    private static Record fetchProgress(DSLContext tx, UUID sourceId) {
        return tx.fetchOne("SELECT * FROM runtime.source_progress WHERE source_id=?::uuid FOR UPDATE", sourceId);
    }

    private static void insertProgress(DSLContext tx, SourceAdvance advance, byte[] requestHash, OffsetDateTime now) {
        OffsetDateTime quietUntil = now.plusMinutes(90);
        tx.execute(
                "INSERT INTO runtime.source_progress (source_id,last_notification_sequence,last_notification_hash,"
                        + "discovered_sequence,discovered_cursor,discovered_source_version,stable_sequence,stable_cursor,"
                        + "stable_source_version,processed_sequence,processed_cursor,processed_source_version,read_kind,"
                        + "read_ref,read_version,read_expires_at,last_real_change_at,quiet_until,updated_at) "
                        + "VALUES (?::uuid,?,?,?,?,?,?,?,?,NULL,NULL,NULL,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
                advance.sourceId(),
                advance.notificationSequence(),
                requestHash,
                advance.discovered().sequence(),
                advance.discovered().cursor(),
                advance.discovered().sourceVersion(),
                sequence(advance.stable()),
                cursor(advance.stable()),
                version(advance.stable()),
                advance.readBinding().kind().name(),
                advance.readBinding().reference(),
                advance.readBinding().version(),
                advance.readBinding().expiresAt(),
                now,
                quietUntil,
                now);
    }

    private static void updateProgress(
            DSLContext tx,
            SourceAdvance advance,
            byte[] requestHash,
            SourceBoundary processed,
            OffsetDateTime lastRealChange,
            OffsetDateTime quietUntil,
            OffsetDateTime now) {
        tx.execute(
                "UPDATE runtime.source_progress SET last_notification_sequence=?,last_notification_hash=?,"
                        + "discovered_sequence=?,discovered_cursor=?,discovered_source_version=?,stable_sequence=?,"
                        + "stable_cursor=?,stable_source_version=?,processed_sequence=?,processed_cursor=?,"
                        + "processed_source_version=?,read_kind=?,read_ref=?,read_version=?,read_expires_at=?::timestamptz,"
                        + "last_real_change_at=?::timestamptz,quiet_until=?::timestamptz,updated_at=?::timestamptz "
                        + "WHERE source_id=?::uuid",
                advance.notificationSequence(),
                requestHash,
                advance.discovered().sequence(),
                advance.discovered().cursor(),
                advance.discovered().sourceVersion(),
                sequence(advance.stable()),
                cursor(advance.stable()),
                version(advance.stable()),
                sequence(processed),
                cursor(processed),
                version(processed),
                advance.readBinding().kind().name(),
                advance.readBinding().reference(),
                advance.readBinding().version(),
                advance.readBinding().expiresAt(),
                lastRealChange,
                quietUntil,
                now,
                advance.sourceId());
    }

    private static UUID upsertPending(
            DSLContext tx,
            UUID sourceId,
            SourceBoundary processed,
            SourceBoundary stable,
            SourceReadBinding binding,
            OffsetDateTime now,
            OffsetDateTime readyAt) {
        if (stable == null || (processed != null && stable.sequence() <= processed.sequence())) {
            return findPendingTaskId(tx, sourceId);
        }
        Record pending = tx.fetchOne(
                "SELECT task_id FROM runtime.formation_task WHERE source_id=?::uuid AND state='PENDING' FOR UPDATE",
                sourceId);
        if (pending == null) {
            UUID taskId = UUID.randomUUID();
            tx.execute(
                    "INSERT INTO runtime.formation_task (task_id,source_id,state,from_sequence,from_cursor,"
                            + "from_source_version,to_sequence,to_cursor,to_source_version,read_kind,read_ref,read_version,"
                            + "read_expires_at,ready_at,generation,created_at,updated_at) "
                            + "VALUES (?::uuid,?::uuid,'PENDING',?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,0,?::timestamptz,?::timestamptz)",
                    taskId,
                    sourceId,
                    sequence(processed),
                    cursor(processed),
                    version(processed),
                    stable.sequence(),
                    stable.cursor(),
                    stable.sourceVersion(),
                    binding.kind().name(),
                    binding.reference(),
                    binding.version(),
                    binding.expiresAt(),
                    readyAt,
                    now,
                    now);
            return taskId;
        }
        UUID taskId = pending.get("task_id", UUID.class);
        tx.execute(
                "UPDATE runtime.formation_task SET to_sequence=?,to_cursor=?,to_source_version=?,read_kind=?,"
                        + "read_ref=?,read_version=?,read_expires_at=?::timestamptz,ready_at=?::timestamptz,"
                        + "updated_at=?::timestamptz WHERE task_id=?::uuid AND state='PENDING'",
                stable.sequence(),
                stable.cursor(),
                stable.sourceVersion(),
                binding.kind().name(),
                binding.reference(),
                binding.version(),
                binding.expiresAt(),
                readyAt,
                now,
                taskId);
        return taskId;
    }

    private static UUID findPendingTaskId(DSLContext tx, UUID sourceId) {
        Record row = tx.fetchOne(
                "SELECT task_id FROM runtime.formation_task WHERE source_id=?::uuid AND state='PENDING'", sourceId);
        return row == null ? null : row.get("task_id", UUID.class);
    }

    private static OffsetDateTime findPendingReadyAt(DSLContext tx, UUID sourceId) {
        Record row = tx.fetchOne(
                "SELECT ready_at FROM runtime.formation_task WHERE source_id=?::uuid AND state='PENDING'", sourceId);
        return row == null ? null : row.get("ready_at", OffsetDateTime.class);
    }

    private static boolean regresses(SourceBoundary current, SourceBoundary next) {
        if (next.sequence() < current.sequence()) {
            return true;
        }
        return next.sequence() == current.sequence() && !next.equals(current);
    }

    private static boolean regressesNullable(SourceBoundary current, SourceBoundary next) {
        if (current == null) {
            return false;
        }
        return next == null || regresses(current, next);
    }

    private static SourceBoundary boundary(Record row, String prefix) {
        return new SourceBoundary(
                row.get(prefix + "_sequence", Long.class),
                row.get(prefix + "_cursor", String.class),
                row.get(prefix + "_source_version", String.class));
    }

    private static SourceBoundary nullableBoundary(Record row, String prefix) {
        Long sequence = row.get(prefix + "_sequence", Long.class);
        return sequence == null
                ? null
                : new SourceBoundary(
                        sequence,
                        row.get(prefix + "_cursor", String.class),
                        row.get(prefix + "_source_version", String.class));
    }

    private static SourceReadBinding readBinding(Record row) {
        return new SourceReadBinding(
                SourceReadBinding.Kind.valueOf(row.get("read_kind", String.class)),
                row.get("read_ref", String.class),
                row.get("read_version", String.class),
                row.get("read_expires_at", OffsetDateTime.class));
    }

    private static Long sequence(SourceBoundary boundary) {
        return boundary == null ? null : boundary.sequence();
    }

    private static String cursor(SourceBoundary boundary) {
        return boundary == null ? null : boundary.cursor();
    }

    private static String version(SourceBoundary boundary) {
        return boundary == null ? null : boundary.sourceVersion();
    }

    private static byte[] digest(SourceAdvance advance) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, advance.sourceId().toString());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(advance.notificationSequence())
                    .array());
            put(digest, advance.discovered());
            put(digest, advance.stable());
            put(digest, advance.readBinding().kind().name());
            put(digest, advance.readBinding().reference());
            put(digest, advance.readBinding().version());
            put(
                    digest,
                    advance.readBinding().expiresAt() == null
                            ? null
                            : advance.readBinding().expiresAt().toInstant().toString());
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void put(MessageDigest digest, SourceBoundary boundary) {
        if (boundary == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        digest.update(
                ByteBuffer.allocate(Long.BYTES).putLong(boundary.sequence()).array());
        put(digest, boundary.cursor());
        put(digest, boundary.sourceVersion());
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
