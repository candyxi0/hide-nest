package io.github.candyxi0.hidenest.evidence.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SourcePayload(
        UUID payloadId,
        UUID sourceUnitId,
        String payloadKind,
        String storeAdapter,
        String objectRef,
        String objectVersionRef,
        String contentType,
        Long sizeBytes,
        byte[] contentHash,
        UUID policyId,
        Long currentPolicyRevisionNo,
        String retentionClass,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {}
