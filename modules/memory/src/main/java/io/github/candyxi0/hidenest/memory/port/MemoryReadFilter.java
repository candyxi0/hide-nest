package io.github.candyxi0.hidenest.memory.port;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Vendor-neutral, bounded seek filter for the current-memory read path. */
public record MemoryReadFilter(
        String state,
        String keyword,
        String memoryType,
        int limit,
        OffsetDateTime afterUpdatedAt,
        UUID afterMemoryId) {}
