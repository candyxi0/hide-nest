package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActorRef(
        UUID actorId,
        String actorKind,
        String stableRef,
        String displayLabel,
        OffsetDateTime createdAt) {}
