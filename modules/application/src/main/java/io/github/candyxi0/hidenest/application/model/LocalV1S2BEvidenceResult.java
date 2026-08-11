package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.UUID;

public record LocalV1S2BEvidenceResult(
        UUID memoryId, UUID currentRevisionId, Long revisionNo, List<LocalV1S2BEvidenceMessage> messages) {

    public LocalV1S2BEvidenceResult {
        messages = List.copyOf(messages);
    }
}
