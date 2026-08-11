package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/** R1-02: confirm only submits the decision binding, not candidate content. */
public record LocalV1S1ConfirmRequest(
        String idempotencyKey,
        byte[] requestHash,
        UUID proposalRevisionId,
        UUID reviewSessionId,
        UUID memoryId,
        UUID policyId,
        byte[] manifestHash) {}
