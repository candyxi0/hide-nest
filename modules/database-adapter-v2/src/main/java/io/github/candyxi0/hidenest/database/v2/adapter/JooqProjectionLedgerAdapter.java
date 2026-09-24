package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Attention;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Binding;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Claim;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.EmbeddingManifest;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Event;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Result;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.ResultKind;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.SchemaManifest;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Settlement;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Target;
import io.github.candyxi0.hidenest.runtime.port.ProjectionLedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** PostgreSQL row locks and unique keys serialize one delivery without blocking unrelated events. */
public final class JooqProjectionLedgerAdapter implements ProjectionLedgerPort {
    private final DSLContext dsl;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryDelay;

    public JooqProjectionLedgerAdapter(DSLContext dsl, Clock clock, int maxAttempts, Duration retryDelay) {
        this.dsl = Objects.requireNonNull(dsl);
        this.clock = Objects.requireNonNull(clock);
        if (maxAttempts < 1
                || maxAttempts > 1000
                || retryDelay == null
                || retryDelay.isZero()
                || retryDelay.isNegative()
                || retryDelay.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("invalid projection retry policy");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Override
    public void register(Target target) {
        requireTarget(target);
        OffsetDateTime now = now();
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            requireWorld(tx, target.worldRef());
            tx.execute(
                    "INSERT INTO runtime.projection_target "
                            + "(world_ref,projection_generation,schema_ref,schema_version,embedding_ref,"
                            + "embedding_dimensions,embedding_version,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?::timestamptz) ON CONFLICT DO NOTHING",
                    target.worldRef(),
                    target.projectionGeneration(),
                    target.schema().schemaRef(),
                    target.schema().version(),
                    target.embedding().modelRef(),
                    target.embedding().dimensions(),
                    target.embedding().version(),
                    now);
            Record row = target(tx, target.worldRef(), target.projectionGeneration());
            if (!target.equals(asTarget(row))) {
                throw new IllegalStateException("projection target manifest conflict");
            }
        });
    }

    @Override
    public List<Event> discover(String worldRef, int projectionGeneration, int limit) {
        requireIdentity(worldRef, projectionGeneration);
        requireLimit(limit);
        OffsetDateTime now = now();
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            requireWorld(tx, worldRef);
            if (target(tx, worldRef, projectionGeneration) == null) {
                throw new IllegalArgumentException("projection target missing");
            }
            // No watermark: the anti-join is reevaluated over committed Outbox rows on every call.
            tx.execute(
                    "INSERT INTO runtime.projection_delivery "
                            + "(world_ref,projection_generation,event_id,state,updated_at) "
                            + "SELECT ?,?,o.event_id,'PENDING',?::timestamptz "
                            + "FROM memory.projection_outbox o WHERE NOT EXISTS "
                            + "(SELECT 1 FROM runtime.projection_delivery d WHERE d.world_ref=? "
                            + "AND d.projection_generation=? AND d.event_id=o.event_id) "
                            + "ORDER BY o.created_at,o.event_id LIMIT ? ON CONFLICT DO NOTHING",
                    worldRef,
                    projectionGeneration,
                    now,
                    worldRef,
                    projectionGeneration,
                    limit);
            List<Event> events = new ArrayList<>();
            for (Record row : tx.fetch(
                    "SELECT d.event_id,o.revision_id,d.state FROM runtime.projection_delivery d "
                            + "JOIN memory.projection_outbox o ON o.event_id=d.event_id "
                            + "WHERE d.world_ref=? AND d.projection_generation=? AND ("
                            + "d.state='PENDING' OR (d.state='RETRY_WAIT' AND d.ready_at<=?::timestamptz) "
                            + "OR (d.state='ATTEMPTING' AND EXISTS "
                            + "(SELECT 1 FROM runtime.projection_attempt a WHERE a.attempt_id=d.current_attempt_id "
                            + "AND a.lease_until<=?::timestamptz))) "
                            + "ORDER BY o.created_at,o.event_id LIMIT ?",
                    worldRef,
                    projectionGeneration,
                    now,
                    now,
                    limit)) {
                events.add(new Event(
                        row.get("event_id", UUID.class),
                        row.get("revision_id", UUID.class),
                        row.get("state", String.class)));
            }
            return List.copyOf(events);
        });
    }

    @Override
    public Optional<Claim> claim(
            String worldRef,
            int projectionGeneration,
            UUID eventId,
            String ownerRef,
            Duration lease,
            String materialDigest) {
        requireIdentity(worldRef, projectionGeneration);
        Objects.requireNonNull(eventId);
        requireOwner(ownerRef);
        requireLease(lease);
        requireDigest(materialDigest);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record delivery = tx.fetchOne(
                    "SELECT * FROM runtime.projection_delivery WHERE world_ref=? "
                            + "AND projection_generation=? AND event_id=?::uuid FOR UPDATE",
                    worldRef,
                    projectionGeneration,
                    eventId);
            if (delivery == null) return Optional.<Claim>empty();
            OffsetDateTime now = now();
            String state = delivery.get("state", String.class);
            if ("APPLIED".equals(state) || "ATTENTION_REQUIRED".equals(state)) return Optional.empty();
            if ("RETRY_WAIT".equals(state)
                    && delivery.get("ready_at", OffsetDateTime.class).isAfter(now)) return Optional.empty();
            if ("ATTEMPTING".equals(state)) {
                UUID oldId = delivery.get("current_attempt_id", UUID.class);
                Record old = tx.fetchOne(
                        "SELECT lease_until FROM runtime.projection_attempt WHERE attempt_id=?::uuid", oldId);
                if (old.get("lease_until", OffsetDateTime.class).isAfter(now)) return Optional.empty();
                tx.execute(
                        "UPDATE runtime.projection_attempt SET state='EXPIRED',finished_at=?::timestamptz "
                                + "WHERE attempt_id=?::uuid AND state='RUNNING'",
                        now,
                        oldId);
            }
            int used = delivery.get("budget_used", Integer.class);
            if (used >= maxAttempts) {
                tx.execute(
                        "UPDATE runtime.projection_delivery SET state='ATTENTION_REQUIRED',"
                                + "current_attempt_id=NULL,attention_code='LEASE_BUDGET_EXHAUSTED',"
                                + "ready_at=NULL,updated_at=?::timestamptz WHERE world_ref=? "
                                + "AND projection_generation=? AND event_id=?::uuid",
                        now,
                        worldRef,
                        projectionGeneration,
                        eventId);
                return Optional.empty();
            }
            int generation = delivery.get("attempt_generation", Integer.class) + 1;
            UUID attemptId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            OffsetDateTime until = now.plus(lease);
            Record target = target(tx, worldRef, projectionGeneration);
            Record outbox =
                    tx.fetchOne("SELECT revision_id FROM memory.projection_outbox WHERE event_id=?::uuid", eventId);
            UUID revision = outbox.get("revision_id", UUID.class);
            tx.execute(
                    "UPDATE runtime.projection_delivery SET state='ATTEMPTING',attempt_generation=?,"
                            + "budget_used=?,current_attempt_id=?::uuid,ready_at=NULL,updated_at=?::timestamptz "
                            + "WHERE world_ref=? AND projection_generation=? AND event_id=?::uuid",
                    generation,
                    used + 1,
                    attemptId,
                    now,
                    worldRef,
                    projectionGeneration,
                    eventId);
            tx.execute(
                    "INSERT INTO runtime.projection_attempt "
                            + "(attempt_id,world_ref,projection_generation,event_id,attempt_generation,task_id,"
                            + "owner_ref,schema_ref,schema_version,embedding_ref,embedding_dimensions,"
                            + "embedding_version,material_digest,expected_revision_id,lease_until,started_at,state) "
                            + "VALUES (?::uuid,?,?,?::uuid,?,?::uuid,?,?,?,?,?,?,?,?::uuid,"
                            + "?::timestamptz,?::timestamptz,'RUNNING')",
                    attemptId,
                    worldRef,
                    projectionGeneration,
                    eventId,
                    generation,
                    taskId,
                    ownerRef,
                    target.get("schema_ref", String.class),
                    target.get("schema_version", String.class),
                    target.get("embedding_ref", String.class),
                    target.get("embedding_dimensions", Integer.class),
                    target.get("embedding_version", String.class),
                    materialDigest,
                    revision,
                    until,
                    now);
            Binding binding = new Binding(
                    taskId,
                    worldRef,
                    eventId,
                    projectionGeneration,
                    generation,
                    new SchemaManifest(
                            target.get("schema_ref", String.class), target.get("schema_version", String.class)),
                    new EmbeddingManifest(
                            target.get("embedding_ref", String.class),
                            target.get("embedding_dimensions", Integer.class),
                            target.get("embedding_version", String.class)),
                    materialDigest,
                    revision);
            return Optional.of(new Claim(attemptId, ownerRef, until, binding));
        });
    }

    @Override
    public boolean renew(Claim claim, Duration lease) {
        Objects.requireNonNull(claim);
        requireLease(lease);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Binding binding = claim.binding();
            Record delivery = lockedDelivery(tx, binding);
            if (delivery == null
                    || !"ATTEMPTING".equals(delivery.get("state", String.class))
                    || !claim.attemptId().equals(delivery.get("current_attempt_id", UUID.class))) return false;
            OffsetDateTime now = now();
            Record attempt = attempt(tx, claim.attemptId());
            if (!matches(attempt, binding, claim.ownerRef())
                    || !"RUNNING".equals(attempt.get("state", String.class))
                    || !attempt.get("lease_until", OffsetDateTime.class).isAfter(now)) return false;
            tx.execute(
                    "UPDATE runtime.projection_attempt SET lease_until=?::timestamptz WHERE attempt_id=?::uuid",
                    now.plus(lease),
                    claim.attemptId());
            return true;
        });
    }

    @Override
    public Settlement settle(Result result) {
        Objects.requireNonNull(result);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Binding binding = result.binding();
            Record delivery = lockedDelivery(tx, binding);
            if (delivery == null) return Settlement.REJECTED;
            OffsetDateTime now = now();
            Record attempt = attempt(tx, result.attemptId());
            if (!matches(attempt, binding, result.ownerRef()) || !validResult(result)) return Settlement.REJECTED;
            String attemptState = attempt.get("state", String.class);
            if (!"RUNNING".equals(attemptState)) return replay(attempt, result);
            if (!"ATTEMPTING".equals(delivery.get("state", String.class))
                    || !result.attemptId().equals(delivery.get("current_attempt_id", UUID.class))
                    || !attempt.get("lease_until", OffsetDateTime.class).isAfter(now)) return Settlement.REJECTED;
            if (result.kind() == ResultKind.APPLIED) {
                tx.execute(
                        "UPDATE runtime.projection_attempt SET state='APPLIED',receipt_digest=?,"
                                + "finished_at=?::timestamptz WHERE attempt_id=?::uuid",
                        result.receiptDigest(),
                        now,
                        result.attemptId());
                tx.execute(
                        "UPDATE runtime.projection_delivery SET state='APPLIED',current_attempt_id=NULL,"
                                + "applied_revision_id=?::uuid,updated_at=?::timestamptz "
                                + "WHERE world_ref=? AND projection_generation=? AND event_id=?::uuid",
                        result.appliedRevisionId(),
                        now,
                        binding.worldRef(),
                        binding.projectionGeneration(),
                        binding.eventId());
                return Settlement.APPLIED;
            }
            boolean attention = result.kind() == ResultKind.PERMANENT_FAILURE
                    || delivery.get("budget_used", Integer.class) >= maxAttempts;
            String next = attention ? "ATTENTION_REQUIRED" : "RETRY_WAIT";
            tx.execute(
                    "UPDATE runtime.projection_attempt SET state=?,failure_code=?,receipt_digest=?,"
                            + "finished_at=?::timestamptz WHERE attempt_id=?::uuid",
                    next,
                    result.failureCode(),
                    result.receiptDigest(),
                    now,
                    result.attemptId());
            tx.execute(
                    "UPDATE runtime.projection_delivery SET state=?,current_attempt_id=NULL,"
                            + "attention_code=?,ready_at=?::timestamptz,updated_at=?::timestamptz "
                            + "WHERE world_ref=? AND projection_generation=? AND event_id=?::uuid",
                    next,
                    attention ? result.failureCode() : null,
                    attention ? null : now.plus(retryDelay),
                    now,
                    binding.worldRef(),
                    binding.projectionGeneration(),
                    binding.eventId());
            return attention ? Settlement.ATTENTION_REQUIRED : Settlement.RETRY_WAIT;
        });
    }

    @Override
    public List<Attention> attention(String worldRef, int projectionGeneration, int limit) {
        requireIdentity(worldRef, projectionGeneration);
        requireLimit(limit);
        List<Attention> result = new ArrayList<>();
        for (Record row : dsl.fetch(
                "SELECT event_id,attention_code FROM runtime.projection_delivery "
                        + "WHERE world_ref=? AND projection_generation=? AND state='ATTENTION_REQUIRED' "
                        + "ORDER BY updated_at,event_id LIMIT ?",
                worldRef,
                projectionGeneration,
                limit)) {
            result.add(new Attention(
                    worldRef,
                    projectionGeneration,
                    row.get("event_id", UUID.class),
                    row.get("attention_code", String.class)));
        }
        return List.copyOf(result);
    }

    @Override
    public boolean recover(String worldRef, int projectionGeneration, UUID eventId, String expectedState) {
        requireIdentity(worldRef, projectionGeneration);
        Objects.requireNonNull(eventId);
        if (!"ATTENTION_REQUIRED".equals(expectedState)) return false;
        return dsl.execute(
                        "UPDATE runtime.projection_delivery SET state='PENDING',budget_used=0,"
                                + "attention_code=NULL,ready_at=NULL,updated_at=?::timestamptz "
                                + "WHERE world_ref=? AND projection_generation=? AND event_id=?::uuid "
                                + "AND state=?",
                        now(),
                        worldRef,
                        projectionGeneration,
                        eventId,
                        expectedState)
                == 1;
    }

    private static Settlement replay(Record attempt, Result result) {
        if (!Objects.equals(attempt.get("receipt_digest", String.class), result.receiptDigest())
                || !Objects.equals(attempt.get("failure_code", String.class), result.failureCode())) {
            return Settlement.REJECTED;
        }
        String state = attempt.get("state", String.class);
        if ("APPLIED".equals(state) && result.kind() == ResultKind.APPLIED) return Settlement.APPLIED;
        if ("RETRY_WAIT".equals(state) && result.kind() == ResultKind.TEMPORARY_FAILURE) return Settlement.RETRY_WAIT;
        if ("ATTENTION_REQUIRED".equals(state) && result.kind() != ResultKind.APPLIED)
            return Settlement.ATTENTION_REQUIRED;
        return Settlement.REJECTED;
    }

    private static boolean validResult(Result result) {
        if (result.attemptId() == null
                || result.binding() == null
                || result.kind() == null
                || !digestValid(result.receiptDigest())) return false;
        if (result.kind() == ResultKind.APPLIED) {
            return result.failureCode() == null
                    && Objects.equals(
                            result.appliedRevisionId(), result.binding().expectedRevisionId());
        }
        if (result.appliedRevisionId() != null) return false;
        if (result.kind() == ResultKind.TEMPORARY_FAILURE) {
            return "INDEX_WRITE_UNAVAILABLE".equals(result.failureCode());
        }
        return "SCHEMA_MISMATCH".equals(result.failureCode())
                || "MATERIAL_INVALID".equals(result.failureCode())
                || "INDEX_WRITE_REJECTED".equals(result.failureCode());
    }

    private static boolean matches(Record row, Binding binding, String ownerRef) {
        if (row == null || binding == null) return false;
        return Objects.equals(row.get("task_id", UUID.class), binding.taskId())
                && Objects.equals(row.get("world_ref", String.class), binding.worldRef())
                && Objects.equals(row.get("event_id", UUID.class), binding.eventId())
                && Objects.equals(row.get("projection_generation", Integer.class), binding.projectionGeneration())
                && Objects.equals(row.get("attempt_generation", Integer.class), binding.attemptGeneration())
                && Objects.equals(
                        row.get("schema_ref", String.class), binding.schema().schemaRef())
                && Objects.equals(
                        row.get("schema_version", String.class),
                        binding.schema().version())
                && Objects.equals(
                        row.get("embedding_ref", String.class),
                        binding.embedding().modelRef())
                && Objects.equals(
                        row.get("embedding_dimensions", Integer.class),
                        binding.embedding().dimensions())
                && Objects.equals(
                        row.get("embedding_version", String.class),
                        binding.embedding().version())
                && Objects.equals(row.get("material_digest", String.class), binding.materialDigest())
                && Objects.equals(row.get("expected_revision_id", UUID.class), binding.expectedRevisionId())
                && Objects.equals(row.get("owner_ref", String.class), ownerRef);
    }

    private static Record lockedDelivery(DSLContext tx, Binding binding) {
        if (binding == null) return null;
        return tx.fetchOne(
                "SELECT * FROM runtime.projection_delivery WHERE world_ref=? "
                        + "AND projection_generation=? AND event_id=?::uuid FOR UPDATE",
                binding.worldRef(),
                binding.projectionGeneration(),
                binding.eventId());
    }

    private static Record attempt(DSLContext tx, UUID attemptId) {
        if (attemptId == null) return null;
        return tx.fetchOne("SELECT * FROM runtime.projection_attempt WHERE attempt_id=?::uuid", attemptId);
    }

    private static Record target(DSLContext tx, String worldRef, int generation) {
        return tx.fetchOne(
                "SELECT * FROM runtime.projection_target WHERE world_ref=? AND projection_generation=?",
                worldRef,
                generation);
    }

    private static Target asTarget(Record row) {
        return new Target(
                row.get("world_ref", String.class),
                row.get("projection_generation", Integer.class),
                new SchemaManifest(row.get("schema_ref", String.class), row.get("schema_version", String.class)),
                new EmbeddingManifest(
                        row.get("embedding_ref", String.class),
                        row.get("embedding_dimensions", Integer.class),
                        row.get("embedding_version", String.class)));
    }

    private static void requireWorld(DSLContext tx, String worldRef) {
        Record row = tx.fetchOne("SELECT world_ref FROM memory.world_binding WHERE singleton=1");
        if (row == null || !worldRef.equals(row.get("world_ref", String.class))) {
            throw new IllegalArgumentException("world binding mismatch");
        }
    }

    private static void requireTarget(Target target) {
        Objects.requireNonNull(target);
        requireIdentity(target.worldRef(), target.projectionGeneration());
        if (target.schema() == null
                || target.embedding() == null
                || target.schema().schemaRef() == null
                || !target.schema().schemaRef().matches("index-schema:[a-z0-9-]+")
                || target.embedding().modelRef() == null
                || !target.embedding().modelRef().matches("embedding:[a-z0-9-]+")
                || target.schema().version() == null
                || target.schema().version().isBlank()
                || target.embedding().version() == null
                || target.embedding().version().isBlank()
                || target.schema().version().length() > 128
                || target.embedding().version().length() > 128
                || target.embedding().dimensions() < 1
                || target.embedding().dimensions() > 4096) {
            throw new IllegalArgumentException("invalid projection target");
        }
    }

    private static void requireIdentity(String worldRef, int generation) {
        if (worldRef == null || !worldRef.matches("world:[a-z0-9-]+") || worldRef.length() > 128 || generation < 0)
            throw new IllegalArgumentException("invalid projection identity");
    }

    private static void requireOwner(String ownerRef) {
        if (ownerRef == null || ownerRef.isBlank() || ownerRef.length() > 128)
            throw new IllegalArgumentException("invalid owner");
    }

    private static void requireLease(Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("invalid lease");
    }

    private static void requireDigest(String digest) {
        if (!digestValid(digest)) throw new IllegalArgumentException("invalid digest");
    }

    private static boolean digestValid(String digest) {
        return digest != null && digest.matches("sha256:[0-9a-f]{64}");
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid limit");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
