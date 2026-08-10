package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConsumerEffect(
        String consumerCode,
        UUID eventId,
        String effectKey,
        OffsetDateTime recordedAt) {}
