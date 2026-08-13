package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/** Successful closeout result with real database-backed run and memory identity. */
public record LocalV1CloseoutReceipt(UUID runId, UUID memoryId, String phase) {

    public static LocalV1CloseoutReceipt of(UUID runId, UUID memoryId, String phase) {
        return new LocalV1CloseoutReceipt(runId, memoryId, phase);
    }
}
