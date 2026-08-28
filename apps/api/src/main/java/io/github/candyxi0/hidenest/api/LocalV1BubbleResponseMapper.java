package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.model.LocalV1BubbleResult;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleRoomPurgeResult;
import io.github.candyxi0.hidenest.contracts.model.BubbleItem;
import io.github.candyxi0.hidenest.contracts.model.BubbleResolveResponse;
import io.github.candyxi0.hidenest.contracts.model.BubbleRoomPurgeResponse;
import io.github.candyxi0.hidenest.contracts.model.MemoryType;

/** Maps only the frozen Bubble model projection into generated HTTP DTOs. */
final class LocalV1BubbleResponseMapper {

    private LocalV1BubbleResponseMapper() {}

    static BubbleResolveResponse resolve(LocalV1BubbleResult result) {
        return new BubbleResolveResponse(
                BubbleResolveResponse.StatusEnum.valueOf(result.status()),
                result.items().stream()
                        .map(item -> new BubbleItem(
                                item.bodyText(), MemoryType.valueOf(item.memoryType()), (long) item.evidenceAgeDays()))
                        .toList());
    }

    static BubbleRoomPurgeResponse purge(LocalV1BubbleRoomPurgeResult result) {
        return new BubbleRoomPurgeResponse(BubbleRoomPurgeResponse.StatusEnum.valueOf(result.status()));
    }
}
