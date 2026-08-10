package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RetrievalTrace(
        UUID traceId,
        UUID requestId,
        UUID threadId,
        UUID turnId,
        String purpose,
        String resultCategory,
        byte[] policyRevisionSetHash,
        List<UUID> consideredIds,
        List<UUID> deliveredIds,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
    public RetrievalTrace {
        consideredIds = consideredIds == null ? null : List.copyOf(consideredIds);
        deliveredIds = deliveredIds == null ? null : List.copyOf(deliveredIds);
    }
}
