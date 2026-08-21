package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Bounded local read request for the current-memory list. {@code memoryType} is the wire enum
 * value (one of the six MemoryType values) or {@code null} for "all types". The seek pair
 * {@code afterUpdatedAt}/{@code afterMemoryId} is either both null (first page) or both non-null.
 */
public record LocalV1S2BListRequest(
        String state,
        String keyword,
        String memoryType,
        int limit,
        OffsetDateTime afterUpdatedAt,
        UUID afterMemoryId) {}
