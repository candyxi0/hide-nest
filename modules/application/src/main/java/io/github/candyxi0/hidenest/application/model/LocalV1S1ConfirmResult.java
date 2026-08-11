package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

public record LocalV1S1ConfirmResult(
        UUID memoryId,
        UUID currentRevisionId,
        Long revisionNo,
        int evidenceCount,
        String resultCategory) {

    public static LocalV1S1ConfirmResult success(
            UUID memoryId, UUID currentRevisionId, Long revisionNo, int evidenceCount) {
        return new LocalV1S1ConfirmResult(
                memoryId, currentRevisionId, revisionNo, evidenceCount, "SUCCEEDED");
    }
}
