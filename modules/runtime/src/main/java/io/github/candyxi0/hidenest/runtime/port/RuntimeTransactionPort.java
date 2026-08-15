package io.github.candyxi0.hidenest.runtime.port;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RuntimeTransactionPort {

    /** Acquire transaction-level advisory lock for idempotency key (L0). */
    void lockIdempotencyKey(String idempotencyKey);

    /** Find existing committed receipt, locking the row. */
    IdempotencyReceipt findReceiptByKey(String idempotencyKey);

    /** Insert COMMITTED receipt at successful transaction completion. */
    void commitReceipt(String idempotencyKey, String operationCode, byte[] requestHash,
            UUID resourceId, String resourceKind, String responseManifest);

    /** Insert governed outbox event. */
    void insertGovernedOutbox(OutboxEvent event);

    /** Insert a capture scope. */
    void insertCaptureScope(CaptureScope scope);

    /** Insert capture scope units. */
    void insertCaptureScopeUnits(List<CaptureScopeUnit> units);

    /**
     * Freeze a capture scope. Returns true iff exactly one row transitioned.
     * Adapter must use precise WHERE (scope_id, frozen_at IS NULL).
     */
    boolean freezeCaptureScope(UUID scopeId, OffsetDateTime frozenAt);

    /** Insert a closeout run. */
    void insertCloseoutRun(CloseoutRun run);

    /**
     * Transition closeout run state with CAS. Returns true iff exactly one row changed.
     * Adapter must use precise WHERE (run_id, expectedState) and SET with affected-row=1 check.
     */
    boolean transitionCloseoutRun(
            UUID runId,
            String expectedState,
            String newState,
            OffsetDateTime startedAt,
            OffsetDateTime terminalAt,
            String failureCode);

    /** Insert a checkpoint (immutable). */
    void insertCheckpoint(Checkpoint checkpoint);

    /** Insert a work artifact. */
    void insertWorkArtifact(WorkArtifact artifact);

    /** Insert a model run. */
    void insertModelRun(ModelRun run);

    /**
     * Transition model run state with CAS. Returns true iff exactly one row changed.
     * Adapter must use precise WHERE (model_run_id, expectedState) and affected-row=1 check.
     */
    boolean transitionModelRun(
            UUID modelRunId,
            String expectedState,
            String newState,
            byte[] outputManifestHash,
            OffsetDateTime terminalAt,
            String failureCode);

    /** Insert a retrieval trace. */
    void insertRetrievalTrace(RetrievalTrace trace);

    /** Insert a context delivery. */
    void insertContextDelivery(ContextDelivery delivery);

    /** Insert context pack delivery items (immutable snapshot facts). */
    void insertContextPackDeliveryItems(List<ContextPackDeliveryItem> items);

    /**
     * Invalidate a context delivery (one-way). Returns true iff exactly one row changed.
     * Adapter must use precise WHERE (delivery_id, invalidated_at IS NULL).
     */
    boolean invalidateContextDelivery(
            UUID deliveryId,
            OffsetDateTime invalidatedAt,
            String invalidationReason);

    /** Insert a consumer effect (immutable). */
    void insertConsumerEffect(ConsumerEffect effect);

    // ── HDM-006 Slice D1: Outbox Worker mechanics ──────────────────────────

    /**
     * Atomically claim and lease the next batch of due outbox events.
     * Uses caller-provided clock (:now) for eligibility, not database clock.
     * Adapter must sort returned DTOs by sequenceNo ASC
     * (UPDATE...RETURNING provides no ordering guarantee).
     * leaseOwner non-null, leaseUntil > now, batchSize 1–100.
     */
    List<ClaimedOutboxEvent> claimAndLeaseOutboxEvents(
            String leaseOwner,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            int batchSize);

    /**
     * Settle a successfully processed outbox event (R1-02).
     * Same transaction: lock event by triple match,
     * INSERT ... ON CONFLICT DO NOTHING ConsumerEffect,
     * transition to SUCCEEDED and clear lease.
     * Returns SETTLED (first time), ALREADY_SETTLED (idempotent replay),
     * or LEASE_LOST (lease expired or wrong owner).
     */
    OutboxSuccessOutcome settleOutboxSuccess(
            UUID eventId,
            String leaseOwner,
            String consumerCode,
            String effectKey,
            OffsetDateTime completedAt);

    /**
     * Record a failure (R1-03). Database atomically decides:
     * - current 0-6 → new 1-7, READY, clear lease, write nextAvailableAt
     * - current 7 → new 8, FINAL_FAILED, clear lease, write completedAt
     * Never produces READY + attempt_count=8.
     * Caller must NOT hardcode attempt=8.
     */
    OutboxFailureSettlement settleOutboxFailure(
            UUID eventId,
            String leaseOwner,
            OffsetDateTime nextAvailableAt,
            OffsetDateTime completedAt,
            String failureCode);

    /**
     * Terminate an event due to STALE/DENIED guard failure (R1-04).
     * One-shot to FINAL_FAILED; no retry, no backoff.
     * Clears lease; attempt_count incremented by 1 but NOT artificially set to 8.
     * No ConsumerEffect, no fence lift, no re-confirmation.
     */
    OutboxTerminalSettlement settleOutboxRejected(
            UUID eventId,
            String leaseOwner,
            OffsetDateTime completedAt,
            String failureCode);

    /**
     * Atomically purge expired work artifacts (R1-06).
     * Single CTE + FOR UPDATE SKIP LOCKED + DELETE ... RETURNING artifact_id.
     * Returns purged artifact IDs in stable order.
     * batchSize 1–100.
     */
    List<UUID> purgeExpiredWorkArtifacts(
            OffsetDateTime cutoff,
            int batchSize);
}
