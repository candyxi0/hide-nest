package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.OutboxEvent;
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
}
