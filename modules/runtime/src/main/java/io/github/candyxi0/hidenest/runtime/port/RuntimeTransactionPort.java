package io.github.candyxi0.hidenest.runtime.port;

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
}
