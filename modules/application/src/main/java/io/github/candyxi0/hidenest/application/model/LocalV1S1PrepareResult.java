package io.github.candyxi0.hidenest.application.model;

import java.util.Set;
import java.util.UUID;

public record LocalV1S1PrepareResult(
        UUID sourceId,
        UUID reviewSessionId,
        UUID proposalRevisionId,
        Set<UUID> hideSelectDecisionIds,
        Set<UUID> anchorIds,
        String bodyText,
        String memoryType,
        UUID perspectiveActorId,
        String state) {

    public static LocalV1S1PrepareResult success(
            UUID sourceId, UUID reviewSessionId, UUID proposalRevisionId,
            Set<UUID> hideSelectDecisionIds, Set<UUID> anchorIds,
            String bodyText, String memoryType, UUID perspectiveActorId) {
        return new LocalV1S1PrepareResult(sourceId, reviewSessionId, proposalRevisionId,
                hideSelectDecisionIds, anchorIds, bodyText, memoryType, perspectiveActorId,
                "PREPARED");
    }
}
