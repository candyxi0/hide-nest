package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One-time automatic appearance fact for a memory revision in a Bubble room. */
public record BubbleRoomRevisionLedgerEntry(
        String spaceKey, String roomKey, UUID memoryRevisionId, String turnKey, OffsetDateTime deliveredAt) {}
