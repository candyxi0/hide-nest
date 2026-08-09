package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.runtime.Tables.IDEMPOTENCY_RECEIPT;
import static io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT;

import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.OffsetDateTime;
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
        // PostgreSQL transaction-level advisory lock.
        // Uses hashtext for deterministic 32-bit lock ID from the key.
        // Automatically released on COMMIT/ROLLBACK.
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
}
