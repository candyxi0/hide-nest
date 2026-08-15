package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryDetail;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryItem;
import io.github.candyxi0.hidenest.contracts.model.MemoryDetail;
import io.github.candyxi0.hidenest.contracts.model.MemoryEvidenceItem;
import io.github.candyxi0.hidenest.contracts.model.MemoryListItem;
import io.github.candyxi0.hidenest.contracts.model.MemoryState;
import io.github.candyxi0.hidenest.contracts.model.MemoryType;
import io.github.candyxi0.hidenest.contracts.model.SourceAvailability;

final class LocalV1MemoryResponseMapper {

    private static final int TITLE_CODE_POINTS = 40;
    private static final int SUMMARY_CODE_POINTS = 120;

    private LocalV1MemoryResponseMapper() {}

    static MemoryListItem listItem(LocalV1S2BMemoryItem source) {
        String body = source.bodyText();
        return new MemoryListItem(
                        source.memoryId(),
                        source.currentRevisionId(),
                        Math.toIntExact(source.revisionNo()),
                        state(source.state()),
                        false, // LOCAL_V1_DERIVED_FALSE: only ACTIVE/ARCHIVED exist and S2B rejects fences.
                        memoryType(source.memoryType()),
                        source.perspectiveActorId().toString(),
                        title(body),
                        truncate(body.strip(), SUMMARY_CODE_POINTS),
                        source.sourceAvailable() ? SourceAvailability.AVAILABLE : SourceAvailability.UNAVAILABLE,
                        source.updatedAt())
                .uncertaintyCode(source.uncertaintyCode());
    }

    static MemoryDetail detail(LocalV1S2BMemoryDetail source) {
        return new MemoryDetail(
                        source.memoryId(),
                        source.currentRevisionId(),
                        Math.toIntExact(source.revisionNo()),
                        source.currentPolicyRevisionNo(),
                        state(source.state()),
                        memoryType(source.memoryType()),
                        source.perspectiveActorId(),
                        source.bodyText(),
                        source.updatedAt(),
                        source.evidenceCount())
                .uncertaintyCode(source.uncertaintyCode());
    }

    static MemoryEvidenceItem evidence(LocalV1S2BEvidenceMessage source) {
        return new MemoryEvidenceItem(
                source.anchorId(),
                source.sourceUnitId(),
                Math.toIntExact(source.ordinal()),
                source.actorId(),
                source.actorKind(),
                source.actorStableRef(),
                source.displayLabel(),
                source.occurredAt(),
                source.bodyText());
    }

    private static MemoryState state(String value) {
        return switch (value) {
            case "ACTIVE" -> MemoryState.ACTIVE;
            case "ARCHIVED" -> MemoryState.ARCHIVED;
            default -> throw new IllegalStateException("Unknown persisted memory state");
        };
    }

    private static MemoryType memoryType(String value) {
        return switch (value) {
            case "Event" -> MemoryType.EVENT;
            case "Claim" -> MemoryType.CLAIM;
            case "Quote" -> MemoryType.QUOTE;
            case "Interpretation" -> MemoryType.INTERPRETATION;
            case "Calibration" -> MemoryType.CALIBRATION;
            case "Principle" -> MemoryType.PRINCIPLE;
            default -> throw new IllegalStateException("Unknown persisted memory type");
        };
    }

    private static String title(String body) {
        return body.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .map(line -> truncate(line, TITLE_CODE_POINTS))
                .orElse("");
    }

    private static String truncate(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
