package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListRequest;
import io.github.candyxi0.hidenest.contracts.model.MemoryListResponse;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1MemoryListController {

    private static final int DEFAULT_LIMIT = 30;
    private final LocalV1S2BQueryCoordinator coordinator;

    public LocalV1MemoryListController(LocalV1S2BQueryCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping(value = "/v1/memories", produces = MediaType.APPLICATION_JSON_VALUE)
    MemoryListResponse list(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "memoryType", required = false) String memoryType,
            @RequestParam(value = "perspectiveActorId", required = false) UUID perspectiveActorId,
            @RequestParam(value = "sourceAvailability", required = false) String sourceAvailability,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        if (memoryType != null || perspectiveActorId != null || sourceAvailability != null) {
            throw new LocalV1RequestException("Unsupported Local V1 list filter");
        }
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > 50) throw new LocalV1RequestException("Invalid limit");
        int offset = cursor == null ? 0 : decodeCursor(cursor);
        String localState = state == null ? "ALL" : state;
        if (!("ALL".equals(localState) || "ACTIVE".equals(localState) || "ARCHIVED".equals(localState))) {
            throw new LocalV1RequestException("Invalid state");
        }
        String keyword = query == null ? "" : query;
        var page = coordinator.listMemories(new LocalV1S2BListRequest(localState, keyword, pageSize, offset));
        List<io.github.candyxi0.hidenest.contracts.model.MemoryListItem> items = page.items().stream()
                .map(LocalV1MemoryResponseMapper::listItem)
                .toList();
        String nextCursor = null;
        if (items.size() == pageSize) {
            var next = coordinator.listMemories(new LocalV1S2BListRequest(localState, keyword, 1, offset + pageSize));
            if (!next.items().isEmpty()) nextCursor = LocalV1CursorCodec.encode(offset + pageSize);
        }
        return new MemoryListResponse(
                        LocalV1RequestContext.requestId(request), ResultCategory.SUCCEEDED, items)
                .nextCursor(nextCursor);
    }

    private static int decodeCursor(String cursor) {
        try {
            return LocalV1CursorCodec.decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw new LocalV1RequestException("Invalid cursor");
        }
    }
}
