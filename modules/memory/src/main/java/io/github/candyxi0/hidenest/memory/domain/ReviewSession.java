package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReviewSession(
        UUID reviewSessionId,
        String state,
        String idempotencyKey,
        byte[] requestHash,
        OffsetDateTime openedAt,
        OffsetDateTime terminalAt) {}
