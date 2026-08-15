package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.runtime.Tables.*;

import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.Checkpoint;
import io.github.candyxi0.hidenest.runtime.domain.ClaimedOutboxEvent;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.ConsumerEffect;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.ContextPackDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.ModelRun;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.domain.OutboxFailureSettlement;
import io.github.candyxi0.hidenest.runtime.domain.OutboxSuccessOutcome;
import io.github.candyxi0.hidenest.runtime.domain.OutboxTerminalSettlement;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.domain.WorkArtifact;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record14;

public class JooqRuntimeTransactionAdapter implements RuntimeTransactionPort {

    private final DSLContext dsl;

    public JooqRuntimeTransactionAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void lockIdempotencyKey(String idempotencyKey) {
        dsl.select(org.jooq.impl.DSL.field(
                "pg_advisory_xact_lock(hashtext({0}))",
                org.jooq.impl.DSL.val(idempotencyKey))).fetch();
    }

    @Override
    public IdempotencyReceipt findReceiptByKey(String idempotencyKey) {
        var r = dsl.selectFrom(IDEMPOTENCY_RECEIPT)
                .where(IDEMPOTENCY_RECEIPT.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .forUpdate()
                .fetchOne();
        if (r == null) return null;
        return new IdempotencyReceipt(
                r.getIdempotencyKey(), r.getOperationCode(), r.getRequestHash(),
                r.getState(), r.getResourceKind(), r.getResourceId(),
                r.getResponseManifest() != null ? r.getResponseManifest().data() : null,
                r.getCreatedAt(), r.getCommittedAt());
    }

    @Override
    public void commitReceipt(String idempotencyKey, String operationCode, byte[] requestHash,
            UUID resourceId, String resourceKind, String responseManifest) {
        OffsetDateTime now = OffsetDateTime.now();
        dsl.insertInto(IDEMPOTENCY_RECEIPT)
                .set(IDEMPOTENCY_RECEIPT.IDEMPOTENCY_KEY, idempotencyKey)
                .set(IDEMPOTENCY_RECEIPT.OPERATION_CODE, operationCode)
                .set(IDEMPOTENCY_RECEIPT.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_RECEIPT.STATE, "COMMITTED")
                .set(IDEMPOTENCY_RECEIPT.RESOURCE_KIND, resourceKind)
                .set(IDEMPOTENCY_RECEIPT.RESOURCE_ID, resourceId)
                .set(IDEMPOTENCY_RECEIPT.RESPONSE_MANIFEST,
                        responseManifest != null ? JSONB.valueOf(responseManifest) : null)
                .set(IDEMPOTENCY_RECEIPT.CREATED_AT, now)
                .set(IDEMPOTENCY_RECEIPT.COMMITTED_AT, now)
                .execute();
    }

    @Override
    public void insertGovernedOutbox(OutboxEvent event) {
        dsl.insertInto(OUTBOX_EVENT)
                .set(OUTBOX_EVENT.EVENT_ID, event.eventId())
                .set(OUTBOX_EVENT.IDEMPOTENCY_KEY, event.idempotencyKey())
                .set(OUTBOX_EVENT.EVENT_CATEGORY, event.eventCategory())
                .set(OUTBOX_EVENT.EVENT_TYPE, event.eventType())
                .set(OUTBOX_EVENT.AGGREGATE_KIND, event.aggregateKind())
                .set(OUTBOX_EVENT.AGGREGATE_ID, event.aggregateId())
                .set(OUTBOX_EVENT.AGGREGATE_REVISION, event.aggregateRevision())
                .set(OUTBOX_EVENT.CONTRACT_VERSION, event.contractVersion())
                .set(OUTBOX_EVENT.PURPOSE, event.purpose())
                .set(OUTBOX_EVENT.POLICY_REVISION, event.policyRevision())
                .set(OUTBOX_EVENT.MANIFEST_HASH, event.manifestHash())
                .set(OUTBOX_EVENT.PAYLOAD_MANIFEST,
                        event.payloadManifest() != null
                                ? JSONB.valueOf(event.payloadManifest()) : null)
                .set(OUTBOX_EVENT.CHANGE_EVENT_ID, event.changeEventId())
                .set(OUTBOX_EVENT.STATE, event.state())
                .set(OUTBOX_EVENT.AVAILABLE_AT, event.availableAt())
                .set(OUTBOX_EVENT.ATTEMPT_COUNT, event.attemptCount())
                .set(OUTBOX_EVENT.MAX_ATTEMPTS, event.maxAttempts())
                .set(OUTBOX_EVENT.CREATED_AT, event.createdAt())
                .execute();
    }

    // ── CaptureScope ──────────────────────────────────────────────────────

    @Override
    public void insertCaptureScope(CaptureScope scope) {
        dsl.insertInto(CAPTURE_SCOPE)
                .set(CAPTURE_SCOPE.SCOPE_ID, scope.scopeId())
                .set(CAPTURE_SCOPE.SOURCE_ID, scope.sourceId())
                .set(CAPTURE_SCOPE.FROM_ORDINAL, scope.fromOrdinal())
                .set(CAPTURE_SCOPE.TO_ORDINAL, scope.toOrdinal())
                .set(CAPTURE_SCOPE.RULE_VERSION, scope.ruleVersion())
                .set(CAPTURE_SCOPE.COVERAGE_CODE, scope.coverageCode())
                .set(CAPTURE_SCOPE.FROZEN_AT, scope.frozenAt())
                .set(CAPTURE_SCOPE.MANIFEST_HASH, scope.manifestHash())
                .set(CAPTURE_SCOPE.CREATED_AT, scope.createdAt())
                .execute();
    }

    @Override
    public void insertCaptureScopeUnits(List<CaptureScopeUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(CAPTURE_SCOPE_UNIT)
                .columns(CAPTURE_SCOPE_UNIT.SCOPE_ID, CAPTURE_SCOPE_UNIT.SOURCE_UNIT_ID,
                        CAPTURE_SCOPE_UNIT.ORDINAL, CAPTURE_SCOPE_UNIT.EXCLUSION_REASON);
        for (var unit : units) {
            insert = insert.values(unit.scopeId(), unit.sourceUnitId(),
                    unit.ordinal(), unit.exclusionReason());
        }
        insert.execute();
    }

    @Override
    public boolean freezeCaptureScope(UUID scopeId, OffsetDateTime frozenAt) {
        int rows = dsl.update(CAPTURE_SCOPE)
                .set(CAPTURE_SCOPE.FROZEN_AT, frozenAt)
                .where(CAPTURE_SCOPE.SCOPE_ID.eq(scopeId))
                .and(CAPTURE_SCOPE.FROZEN_AT.isNull())
                .execute();
        return rows == 1;
    }

    // ── CloseoutRun ───────────────────────────────────────────────────────

    @Override
    public void insertCloseoutRun(CloseoutRun run) {
        dsl.insertInto(CLOSEOUT_RUN)
                .set(CLOSEOUT_RUN.RUN_ID, run.runId())
                .set(CLOSEOUT_RUN.SCOPE_ID, run.scopeId())
                .set(CLOSEOUT_RUN.STATE, run.state())
                .set(CLOSEOUT_RUN.RETRY_OF, run.retryOf())
                .set(CLOSEOUT_RUN.SUBMISSION_ID, run.submissionId())
                .set(CLOSEOUT_RUN.STARTED_AT, run.startedAt())
                .set(CLOSEOUT_RUN.TERMINAL_AT, run.terminalAt())
                .set(CLOSEOUT_RUN.FAILURE_CODE, run.failureCode())
                .set(CLOSEOUT_RUN.CREATED_AT, run.createdAt())
                .execute();
    }

    @Override
    public boolean transitionCloseoutRun(
            UUID runId, String expectedState, String newState,
            OffsetDateTime startedAt, OffsetDateTime terminalAt, String failureCode) {
        int rows = dsl.update(CLOSEOUT_RUN)
                .set(CLOSEOUT_RUN.STATE, newState)
                .set(CLOSEOUT_RUN.STARTED_AT, startedAt)
                .set(CLOSEOUT_RUN.TERMINAL_AT, terminalAt)
                .set(CLOSEOUT_RUN.FAILURE_CODE, failureCode)
                .where(CLOSEOUT_RUN.RUN_ID.eq(runId))
                .and(CLOSEOUT_RUN.STATE.eq(expectedState))
                .execute();
        return rows == 1;
    }

    // ── Checkpoint ────────────────────────────────────────────────────────

    @Override
    public void insertCheckpoint(Checkpoint checkpoint) {
        dsl.insertInto(CHECKPOINT)
                .set(CHECKPOINT.CHECKPOINT_ID, checkpoint.checkpointId())
                .set(CHECKPOINT.RUN_KIND, checkpoint.runKind())
                .set(CHECKPOINT.RUN_ID, checkpoint.runId())
                .set(CHECKPOINT.SEQUENCE_NO, checkpoint.sequenceNo())
                .set(CHECKPOINT.MANIFEST_HASH, checkpoint.manifestHash())
                .set(CHECKPOINT.OBJECT_REF, checkpoint.objectRef())
                .set(CHECKPOINT.CREATED_AT, checkpoint.createdAt())
                .execute();
    }

    // ── WorkArtifact ──────────────────────────────────────────────────────

    @Override
    public void insertWorkArtifact(WorkArtifact artifact) {
        dsl.insertInto(WORK_ARTIFACT)
                .set(WORK_ARTIFACT.ARTIFACT_ID, artifact.artifactId())
                .set(WORK_ARTIFACT.RUN_ID, artifact.runId())
                .set(WORK_ARTIFACT.ARTIFACT_KIND, artifact.artifactKind())
                .set(WORK_ARTIFACT.OBJECT_REF, artifact.objectRef())
                .set(WORK_ARTIFACT.CONTENT_HASH, artifact.contentHash())
                .set(WORK_ARTIFACT.EXPIRES_AT, artifact.expiresAt())
                .set(WORK_ARTIFACT.CREATED_AT, artifact.createdAt())
                .execute();
    }

    // ── ModelRun ──────────────────────────────────────────────────────────

    @Override
    public void insertModelRun(ModelRun run) {
        dsl.insertInto(MODEL_RUN)
                .set(MODEL_RUN.MODEL_RUN_ID, run.modelRunId())
                .set(MODEL_RUN.ROLE_CODE, run.roleCode())
                .set(MODEL_RUN.PROVIDER_MANIFEST_ID, run.providerManifestId())
                .set(MODEL_RUN.STATE, run.state())
                .set(MODEL_RUN.INPUT_MANIFEST_HASH, run.inputManifestHash())
                .set(MODEL_RUN.OUTPUT_MANIFEST_HASH, run.outputManifestHash())
                .set(MODEL_RUN.RETRY_OF, run.retryOf())
                .set(MODEL_RUN.STARTED_AT, run.startedAt())
                .set(MODEL_RUN.TERMINAL_AT, run.terminalAt())
                .set(MODEL_RUN.FAILURE_CODE, run.failureCode())
                .execute();
    }

    @Override
    public boolean transitionModelRun(
            UUID modelRunId, String expectedState, String newState,
            byte[] outputManifestHash, OffsetDateTime terminalAt, String failureCode) {
        int rows = dsl.update(MODEL_RUN)
                .set(MODEL_RUN.STATE, newState)
                .set(MODEL_RUN.OUTPUT_MANIFEST_HASH, outputManifestHash)
                .set(MODEL_RUN.TERMINAL_AT, terminalAt)
                .set(MODEL_RUN.FAILURE_CODE, failureCode)
                .where(MODEL_RUN.MODEL_RUN_ID.eq(modelRunId))
                .and(MODEL_RUN.STATE.eq(expectedState))
                .execute();
        return rows == 1;
    }

    // ── RetrievalTrace ────────────────────────────────────────────────────

    @Override
    public void insertRetrievalTrace(RetrievalTrace trace) {
        dsl.insertInto(RETRIEVAL_TRACE)
                .set(RETRIEVAL_TRACE.TRACE_ID, trace.traceId())
                .set(RETRIEVAL_TRACE.REQUEST_ID, trace.requestId())
                .set(RETRIEVAL_TRACE.THREAD_ID, trace.threadId())
                .set(RETRIEVAL_TRACE.TURN_ID, trace.turnId())
                .set(RETRIEVAL_TRACE.PURPOSE, trace.purpose())
                .set(RETRIEVAL_TRACE.RESULT_CATEGORY, trace.resultCategory())
                .set(RETRIEVAL_TRACE.POLICY_REVISION_SET_HASH, trace.policyRevisionSetHash())
                .set(RETRIEVAL_TRACE.CONSIDERED_IDS,
                        trace.consideredIds() != null
                                ? trace.consideredIds().toArray(UUID[]::new) : null)
                .set(RETRIEVAL_TRACE.DELIVERED_IDS,
                        trace.deliveredIds() != null
                                ? trace.deliveredIds().toArray(UUID[]::new) : null)
                .set(RETRIEVAL_TRACE.CREATED_AT, trace.createdAt())
                .set(RETRIEVAL_TRACE.EXPIRES_AT, trace.expiresAt())
                .execute();
    }

    // ── ContextDelivery ───────────────────────────────────────────────────

    @Override
    public void insertContextDelivery(ContextDelivery delivery) {
        dsl.insertInto(CONTEXT_DELIVERY)
                .set(CONTEXT_DELIVERY.DELIVERY_ID, delivery.deliveryId())
                .set(CONTEXT_DELIVERY.REQUEST_ID, delivery.requestId())
                .set(CONTEXT_DELIVERY.THREAD_ID, delivery.threadId())
                .set(CONTEXT_DELIVERY.TURN_ID, delivery.turnId())
                .set(CONTEXT_DELIVERY.PURPOSE, delivery.purpose())
                .set(CONTEXT_DELIVERY.POLICY_REVISION_SET_HASH, delivery.policyRevisionSetHash())
                .set(CONTEXT_DELIVERY.MANIFEST_HASH, delivery.manifestHash())
                .set(CONTEXT_DELIVERY.DELIVERED_AT, delivery.deliveredAt())
                .set(CONTEXT_DELIVERY.EXPIRES_AT, delivery.expiresAt())
                .set(CONTEXT_DELIVERY.INVALIDATED_AT, delivery.invalidatedAt())
                .set(CONTEXT_DELIVERY.INVALIDATION_REASON, delivery.invalidationReason())
                .execute();
    }

    @Override
    public void insertContextPackDeliveryItems(List<ContextPackDeliveryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ContextPackDeliveryItem item : items) {
            dsl.execute(
                    "INSERT INTO runtime.context_pack_delivery_item ("
                            + "delivery_id, ordinal, memory_revision_id, policy_revision_no, score) "
                            + "VALUES (?::uuid, ?::bigint, ?::uuid, ?::bigint, ?::double precision)",
                    item.deliveryId(),
                    item.ordinal(),
                    item.memoryRevisionId(),
                    item.policyRevisionNo(),
                    item.score());
        }
    }

    @Override
    public boolean invalidateContextDelivery(
            UUID deliveryId, OffsetDateTime invalidatedAt, String invalidationReason) {
        int rows = dsl.update(CONTEXT_DELIVERY)
                .set(CONTEXT_DELIVERY.INVALIDATED_AT, invalidatedAt)
                .set(CONTEXT_DELIVERY.INVALIDATION_REASON, invalidationReason)
                .where(CONTEXT_DELIVERY.DELIVERY_ID.eq(deliveryId))
                .and(CONTEXT_DELIVERY.INVALIDATED_AT.isNull())
                .and(CONTEXT_DELIVERY.INVALIDATION_REASON.isNull())
                .execute();
        return rows == 1;
    }

    // ── ConsumerEffect ────────────────────────────────────────────────────

    @Override
    public void insertConsumerEffect(ConsumerEffect effect) {
        dsl.insertInto(CONSUMER_EFFECT)
                .set(CONSUMER_EFFECT.CONSUMER_CODE, effect.consumerCode())
                .set(CONSUMER_EFFECT.EVENT_ID, effect.eventId())
                .set(CONSUMER_EFFECT.EFFECT_KEY, effect.effectKey())
                .set(CONSUMER_EFFECT.RECORDED_AT, effect.recordedAt())
                .execute();
    }

    // ── HDM-006 Slice D1: Outbox Worker mechanics ─────────────────────────

    @Override
    public List<ClaimedOutboxEvent> claimAndLeaseOutboxEvents(
            String leaseOwner,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            int batchSize) {
        // Parameter gates (R1-01 + R1-06)
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        if (now == null) {
            throw new NullPointerException("now must not be null");
        }
        if (leaseUntil == null) {
            throw new NullPointerException("leaseUntil must not be null");
        }
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be > now");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be 1-100");
        }

        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now.toInstant());
        java.sql.Timestamp leaseTs = java.sql.Timestamp.from(leaseUntil.toInstant());

        // Single CTE: SELECT eligible rows → UPDATE lease → RETURNING
        var result = dsl.resultQuery(
                "WITH next_batch AS (" +
                "  SELECT event_id" +
                "  FROM runtime.outbox_event" +
                "  WHERE (state = 'READY' AND available_at <= ?::timestamptz)" +
                "     OR (state = 'LEASED' AND lease_until <= ?::timestamptz)" +
                "  ORDER BY available_at ASC, sequence_no ASC" +
                "  LIMIT ?" +
                "  FOR UPDATE SKIP LOCKED" +
                ") " +
                "UPDATE runtime.outbox_event" +
                " SET state = 'LEASED'," +
                "     lease_owner = ?," +
                "     lease_until = ?::timestamptz" +
                " FROM next_batch" +
                " WHERE runtime.outbox_event.event_id = next_batch.event_id" +
                " RETURNING" +
                "   runtime.outbox_event.event_id," +
                "   runtime.outbox_event.sequence_no," +
                "   runtime.outbox_event.event_category," +
                "   runtime.outbox_event.event_type," +
                "   runtime.outbox_event.aggregate_kind," +
                "   runtime.outbox_event.aggregate_id," +
                "   runtime.outbox_event.aggregate_revision," +
                "   runtime.outbox_event.purpose," +
                "   runtime.outbox_event.policy_revision," +
                "   runtime.outbox_event.manifest_hash," +
                "   runtime.outbox_event.payload_manifest," +
                "   runtime.outbox_event.change_event_id," +
                "   runtime.outbox_event.attempt_count," +
                "   runtime.outbox_event.max_attempts",
                nowTs, nowTs, batchSize, leaseOwner, leaseTs)
                .fetch();

        List<ClaimedOutboxEvent> events = new ArrayList<>();
        for (var r : result) {
            events.add(mapClaimed(r));
        }
        // R1-01: UPDATE...RETURNING has no ordering guarantee; sort before return
        events.sort(Comparator.comparingLong(ClaimedOutboxEvent::sequenceNo));
        return events;
    }

    private ClaimedOutboxEvent mapClaimed(org.jooq.Record r) {
        return new ClaimedOutboxEvent(
                r.get(0, UUID.class),                         // event_id
                r.get(1, Long.class),                          // sequence_no
                r.get(2, String.class),                        // event_category
                r.get(3, String.class),                        // event_type
                r.get(4, String.class),                        // aggregate_kind
                r.get(5, UUID.class),                          // aggregate_id
                r.get(6, Long.class),                          // aggregate_revision
                r.get(7, String.class),                        // purpose
                r.get(8, Long.class),                          // policy_revision
                r.get(9, byte[].class),                        // manifest_hash
                r.get(10, String.class),                       // payload_manifest
                r.get(11, UUID.class),                         // change_event_id
                r.get(12, Short.class),                        // attempt_count
                r.get(13, Short.class)                         // max_attempts
        );
    }

    @Override
    public OutboxSuccessOutcome settleOutboxSuccess(
            UUID eventId,
            String leaseOwner,
            String consumerCode,
            String effectKey,
            OffsetDateTime completedAt) {
        // R1-06: param gates
        if (eventId == null) throw new NullPointerException("eventId must not be null");
        if (leaseOwner == null || leaseOwner.isBlank()) throw new IllegalArgumentException("leaseOwner must not be blank");
        if (consumerCode == null || consumerCode.isBlank()) throw new IllegalArgumentException("consumerCode must not be blank");
        if (effectKey == null || effectKey.isBlank()) throw new IllegalArgumentException("effectKey must not be blank");
        if (completedAt == null) throw new NullPointerException("completedAt must not be null");

        return dsl.transactionResult(trCtx -> {
            var tx = trCtx.dsl();

            // 1. Lock and verify triple match
            var locked = tx.selectFrom(OUTBOX_EVENT)
                    .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                    .and(OUTBOX_EVENT.STATE.eq("LEASED"))
                    .and(OUTBOX_EVENT.LEASE_OWNER.eq(leaseOwner))
                    .forUpdate()
                    .fetchOne();

            if (locked == null) {
                var existing = tx.selectFrom(OUTBOX_EVENT)
                        .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                        .fetchOne();
                if (existing != null && "SUCCEEDED".equals(existing.getState())) {
                    int count = tx.selectCount()
                            .from(CONSUMER_EFFECT)
                            .where(CONSUMER_EFFECT.CONSUMER_CODE.eq(consumerCode))
                            .and(CONSUMER_EFFECT.EVENT_ID.eq(eventId))
                            .and(CONSUMER_EFFECT.EFFECT_KEY.eq(effectKey))
                            .fetchOne(0, int.class);
                    if (count > 0) return OutboxSuccessOutcome.ALREADY_SETTLED;
                }
                return OutboxSuccessOutcome.LEASE_LOST;
            }

            // 2. INSERT ... ON CONFLICT DO NOTHING
            int inserted = tx.insertInto(CONSUMER_EFFECT,
                    CONSUMER_EFFECT.CONSUMER_CODE, CONSUMER_EFFECT.EVENT_ID,
                    CONSUMER_EFFECT.EFFECT_KEY, CONSUMER_EFFECT.RECORDED_AT)
                    .values(consumerCode, eventId, effectKey, completedAt)
                    .onConflictDoNothing()
                    .execute();

            // R1-02: marker already existed → convergence (R2-02: must UPDATE exactly 1)
            if (inserted == 0) {
                int convRows = tx.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.STATE, "SUCCEEDED")
                        .set(OUTBOX_EVENT.LEASE_OWNER, (String) null)
                        .set(OUTBOX_EVENT.LEASE_UNTIL, (OffsetDateTime) null)
                        .set(OUTBOX_EVENT.COMPLETED_AT, completedAt)
                        .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                        .and(OUTBOX_EVENT.STATE.eq("LEASED"))
                        .and(OUTBOX_EVENT.LEASE_OWNER.eq(leaseOwner))
                        .execute();
                if (convRows != 1) {
                    throw new IllegalStateException(
                            "HDM006_INVARIANT: settleOutboxSuccess marker convergence UPDATE affected "
                            + convRows + " rows for event " + eventId + " (expected 1); transaction rolls back");
                }
                return OutboxSuccessOutcome.ALREADY_SETTLED;
            }

            // 3. Transition to SUCCEEDED
            int rows = tx.update(OUTBOX_EVENT)
                    .set(OUTBOX_EVENT.STATE, "SUCCEEDED")
                    .set(OUTBOX_EVENT.LEASE_OWNER, (String) null)
                    .set(OUTBOX_EVENT.LEASE_UNTIL, (OffsetDateTime) null)
                    .set(OUTBOX_EVENT.COMPLETED_AT, completedAt)
                    .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                    .and(OUTBOX_EVENT.STATE.eq("LEASED"))
                    .and(OUTBOX_EVENT.LEASE_OWNER.eq(leaseOwner))
                    .execute();

            // R1-05: UPDATE must affect exactly 1 row; otherwise rollback
            if (rows != 1) {
                throw new IllegalStateException(
                        "HDM006_INVARIANT: settleOutboxSuccess UPDATE affected " + rows
                        + " rows for event " + eventId + " (expected 1); transaction rolls back");
            }

            return OutboxSuccessOutcome.SETTLED;
        });
    }

    @Override
    public OutboxFailureSettlement settleOutboxFailure(
            UUID eventId,
            String leaseOwner,
            OffsetDateTime nextAvailableAt,
            OffsetDateTime completedAt,
            String failureCode) {
        // R1-06: param gates
        if (eventId == null) throw new NullPointerException("eventId must not be null");
        if (leaseOwner == null || leaseOwner.isBlank()) throw new IllegalArgumentException("leaseOwner must not be blank");
        if (failureCode == null || failureCode.isBlank()) throw new IllegalArgumentException("failureCode must not be blank");
        if (nextAvailableAt == null) throw new NullPointerException("nextAvailableAt must not be null");
        if (completedAt == null) throw new NullPointerException("completedAt must not be null");

        return dsl.transactionResult(trCtx -> {
            var tx = trCtx.dsl();
            var locked = tx.selectFrom(OUTBOX_EVENT)
                    .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                    .forUpdate()
                    .fetchOne();

            if (locked == null) {
                return new OutboxFailureSettlement.LeaseLost("UNKNOWN", (short) 0);
            }
            if ("SUCCEEDED".equals(locked.getState()) || "FINAL_FAILED".equals(locked.getState())) {
                return new OutboxFailureSettlement.Terminal(
                        locked.getState(), locked.getAttemptCount());
            }
            if (!"LEASED".equals(locked.getState()) || !leaseOwner.equals(locked.getLeaseOwner())) {
                return new OutboxFailureSettlement.LeaseLost(
                        locked.getState(), locked.getAttemptCount());
            }

            int newAttempt = locked.getAttemptCount() + 1;
            if (newAttempt >= 1 && newAttempt <= 7) {
                tx.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.STATE, "READY")
                        .set(OUTBOX_EVENT.LEASE_OWNER, (String) null)
                        .set(OUTBOX_EVENT.LEASE_UNTIL, (OffsetDateTime) null)
                        .set(OUTBOX_EVENT.ATTEMPT_COUNT, (short) newAttempt)
                        .set(OUTBOX_EVENT.AVAILABLE_AT, nextAvailableAt)
                        .set(OUTBOX_EVENT.LAST_FAILURE_CODE, failureCode)
                        .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                        .execute();
                return new OutboxFailureSettlement.RetryScheduled((short) newAttempt);
            } else {
                tx.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.STATE, "FINAL_FAILED")
                        .set(OUTBOX_EVENT.LEASE_OWNER, (String) null)
                        .set(OUTBOX_EVENT.LEASE_UNTIL, (OffsetDateTime) null)
                        .set(OUTBOX_EVENT.ATTEMPT_COUNT, (short) newAttempt)
                        .set(OUTBOX_EVENT.COMPLETED_AT, completedAt)
                        .set(OUTBOX_EVENT.LAST_FAILURE_CODE, failureCode)
                        .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                        .execute();
                return new OutboxFailureSettlement.FinalFailed((short) newAttempt);
            }
        });
    }

    @Override
    public OutboxTerminalSettlement settleOutboxRejected(
            UUID eventId,
            String leaseOwner,
            OffsetDateTime completedAt,
            String failureCode) {
        // R1-06: param gates
        if (eventId == null) throw new NullPointerException("eventId must not be null");
        if (leaseOwner == null || leaseOwner.isBlank()) throw new IllegalArgumentException("leaseOwner must not be blank");
        if (failureCode == null || failureCode.isBlank()) throw new IllegalArgumentException("failureCode must not be blank");
        if (completedAt == null) throw new NullPointerException("completedAt must not be null");

        return dsl.transactionResult(trCtx -> {
            var tx = trCtx.dsl();
            var locked = tx.selectFrom(OUTBOX_EVENT)
                    .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                    .forUpdate()
                    .fetchOne();

            if (locked == null) {
                return new OutboxTerminalSettlement.LeaseLost("UNKNOWN");
            }
            if ("SUCCEEDED".equals(locked.getState()) || "FINAL_FAILED".equals(locked.getState())) {
                return new OutboxTerminalSettlement.AlreadyTerminal(locked.getState());
            }
            if (!"LEASED".equals(locked.getState()) || !leaseOwner.equals(locked.getLeaseOwner())) {
                return new OutboxTerminalSettlement.LeaseLost(locked.getState());
            }

            int currentAttempt = locked.getAttemptCount();
            int newAttempt = Math.min(currentAttempt + 1, 8);

            tx.update(OUTBOX_EVENT)
                    .set(OUTBOX_EVENT.STATE, "FINAL_FAILED")
                    .set(OUTBOX_EVENT.LEASE_OWNER, (String) null)
                    .set(OUTBOX_EVENT.LEASE_UNTIL, (OffsetDateTime) null)
                    .set(OUTBOX_EVENT.ATTEMPT_COUNT, (short) newAttempt)
                    .set(OUTBOX_EVENT.COMPLETED_AT, completedAt)
                    .set(OUTBOX_EVENT.LAST_FAILURE_CODE, failureCode)
                    .where(OUTBOX_EVENT.EVENT_ID.eq(eventId))
                    .execute();

            return new OutboxTerminalSettlement.Rejected();
        });
    }

    @Override
    public List<UUID> purgeExpiredWorkArtifacts(OffsetDateTime cutoff, int batchSize) {
        // R1-06: param gates
        if (cutoff == null) throw new NullPointerException("cutoff must not be null");
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be 1-100");
        }

        java.sql.Timestamp cutoffTs = java.sql.Timestamp.from(cutoff.toInstant());

        // R1-03: eligibility is expires_at <= cutoff (not <), CTE retains expires_at/artifact_id for stable sort
        var result = dsl.resultQuery(
                "WITH expired AS (" +
                "  SELECT artifact_id, expires_at" +
                "  FROM runtime.work_artifact" +
                "  WHERE expires_at <= ?::timestamptz" +
                "  ORDER BY expires_at ASC, artifact_id ASC" +
                "  LIMIT ?" +
                "  FOR UPDATE SKIP LOCKED" +
                ") " +
                "DELETE FROM runtime.work_artifact" +
                " USING expired" +
                " WHERE runtime.work_artifact.artifact_id = expired.artifact_id" +
                " RETURNING expired.expires_at, runtime.work_artifact.artifact_id",
                cutoffTs, batchSize)
                .fetch();

        // R1-03: RETURNING includes (expires_at, artifact_id); sort in Java
        // (PostgreSQL DELETE...RETURNING does not support ORDER BY)
        List<java.util.AbstractMap.SimpleImmutableEntry<OffsetDateTime, UUID>> temp = new ArrayList<>();
        for (var r : result) {
            java.sql.Timestamp ts = r.get(0, java.sql.Timestamp.class);
            OffsetDateTime exp = ts != null ? ts.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC) : null;
            temp.add(new java.util.AbstractMap.SimpleImmutableEntry<>(exp, r.get(1, UUID.class)));
        }
        temp.sort(java.util.Comparator
                .<java.util.AbstractMap.SimpleImmutableEntry<OffsetDateTime, UUID>, OffsetDateTime>comparing(
                        java.util.AbstractMap.SimpleImmutableEntry::getKey,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(java.util.AbstractMap.SimpleImmutableEntry::getValue));
        List<UUID> ids = new ArrayList<>();
        for (var e : temp) ids.add(e.getValue());
        return ids;
    }
}
