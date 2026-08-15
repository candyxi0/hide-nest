package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackMemory;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackResult;
import io.github.candyxi0.hidenest.contracts.model.ContextPackMemory;
import io.github.candyxi0.hidenest.contracts.model.ContextPackResponse;
import io.github.candyxi0.hidenest.contracts.model.MemoryType;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import java.math.BigDecimal;
import java.util.List;

/** Maps the minimal context pack application result into the generated OpenAPI DTO. */
final class LocalV1ContextPackResponseMapper {

    private LocalV1ContextPackResponseMapper() {}

    static ContextPackResponse map(LocalV1ContextPackResult result) {
        List<ContextPackMemory> memories = result.memories().stream()
                .map(LocalV1ContextPackResponseMapper::memory)
                .toList();
        return new ContextPackResponse(
                        result.requestId(),
                        ResultCategory.valueOf(result.resultCategory()),
                        result.deliveryId(),
                        result.threadId(),
                        result.turnId(),
                        result.purpose(),
                        result.policyRevisionSet(),
                        result.issuedAt(),
                        result.expiresAt(),
                        result.budgetLimited())
                .memories(memories);
    }

    private static ContextPackMemory memory(LocalV1ContextPackMemory source) {
        return new ContextPackMemory(
                source.memoryId(),
                source.memoryRevisionId(),
                source.revisionNo(),
                source.policyRevisionNo(),
                MemoryType.valueOf(source.memoryType()),
                source.bodyText(),
                BigDecimal.valueOf(source.score()));
    }
}
