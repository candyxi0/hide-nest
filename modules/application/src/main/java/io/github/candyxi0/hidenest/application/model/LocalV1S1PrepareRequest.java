package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** R1-01: only selected evidence messages; no full-chat input. */
public record LocalV1S1PrepareRequest(
        String idempotencyKey,
        byte[] requestHash,
        UUID perspectiveActorId,
        String memoryType,
        String bodyText,
        byte[] bodyHash,
        List<EvidenceMessage> selectedEvidenceMessages,
        List<AnchorInput> anchors) {

    public record EvidenceMessage(
            UUID sourceUnitId,
            UUID actorId,
            Long ordinal,
            String externalUnitRef,
            OffsetDateTime occurredAt,
            String bodyText) {}

    public record AnchorInput(
            UUID anchorId,
            List<AnchorUnitRef> units) {

        public record AnchorUnitRef(
                UUID sourceUnitId,
                Long fromOffset,
                Long toOffset,
                Long ordinal) {}
    }
}
