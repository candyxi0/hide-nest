package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A list-only current memory projection; it intentionally has no evidence metadata. */
public record LocalV1S2BMemoryItem(
        UUID memoryId,
        UUID currentRevisionId,
        String state,
        Long revisionNo,
        String memoryType,
        UUID perspectiveActorId,
        String bodyText,
        String uncertaintyCode,
        OffsetDateTime updatedAt,
        int evidenceCount,
        boolean sourceAvailable) {

    /** Backward-compatible S2B preview derived from the same canonical body text. */
    public String preview() {
        int count = bodyText.codePointCount(0, bodyText.length());
        return count <= 120 ? bodyText : bodyText.substring(0, bodyText.offsetByCodePoints(0, 120));
    }
}
