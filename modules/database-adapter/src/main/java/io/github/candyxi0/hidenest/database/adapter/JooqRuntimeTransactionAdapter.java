package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.runtime.Tables.*;

import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.Checkpoint;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.ConsumerEffect;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.ModelRun;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.domain.WorkArtifact;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

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
}
