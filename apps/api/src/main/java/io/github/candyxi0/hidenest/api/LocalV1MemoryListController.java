package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryItem;
import io.github.candyxi0.hidenest.contracts.model.MemoryListResponse;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Set<String> WIRE_TYPES =
            Set.of("EVENT", "CLAIM", "QUOTE", "INTERPRETATION", "CALIBRATION", "PRINCIPLE");
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
        if (perspectiveActorId != null || sourceAvailability != null) {
            throw new LocalV1RequestException("Unsupported Local V1 list filter");
        }
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > 50) throw new LocalV1RequestException("Invalid limit");
        String localState = state == null ? "ALL" : state;
        if (!("ALL".equals(localState) || "ACTIVE".equals(localState) || "ARCHIVED".equals(localState))) {
            throw new LocalV1RequestException("Invalid state");
        }
        // Wire type is one of the six formal values; null (omitted) means all types. Any other
        // value (explicit blank, unknown, case variant) is rejected, never silently treated as all.
        String wireType = memoryType;
        if (wireType != null && !WIRE_TYPES.contains(wireType)) {
            throw new LocalV1RequestException("Invalid memoryType");
        }
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String typeOrAll = wireType == null ? "ALL" : wireType;
        String canonicalFilter =
                LocalV1CursorCodec.encodeFilterCanonical(localState, keyword, typeOrAll);

        OffsetDateTime afterUpdatedAt = null;
        UUID afterMemoryId = null;
        if (cursor != null) {
            LocalV1CursorCodec.Decoded decoded = decodeCursor(cursor, canonicalFilter);
            afterUpdatedAt = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(
                            decoded.lastUpdatedAtMicros() / 1_000_000L,
                            (decoded.lastUpdatedAtMicros() % 1_000_000L) * 1000L),
                    ZoneOffset.UTC);
            afterMemoryId = decoded.lastMemoryId();
        }

        var page = coordinator.listMemories(new LocalV1S2BListRequest(
                localState, keyword, wireType, pageSize, afterUpdatedAt, afterMemoryId));
        List<io.github.candyxi0.hidenest.contracts.model.MemoryListItem> items =
                page.items().stream().map(LocalV1MemoryResponseMapper::listItem).toList();

        String nextCursor = null;
        if (!page.items().isEmpty() && page.items().size() == pageSize) {
            LocalV1S2BMemoryItem last = page.items().get(page.items().size() - 1);
            var probe = coordinator.listMemories(new LocalV1S2BListRequest(
                    localState, keyword, wireType, 1, last.updatedAt(), last.memoryId()));
            if (!probe.items().isEmpty()) {
                nextCursor = LocalV1CursorCodec.encode(
                        micros(last.updatedAt()), last.memoryId(), canonicalFilter);
            }
        }
        return new MemoryListResponse(
                        LocalV1RequestContext.requestId(request), ResultCategory.SUCCEEDED, items)
                .nextCursor(nextCursor);
    }

    private static LocalV1CursorCodec.Decoded decodeCursor(String cursor, String canonicalFilter) {
        try {
            return LocalV1CursorCodec.decode(cursor, canonicalFilter);
        } catch (IllegalArgumentException exception) {
            throw new LocalV1RequestException("Invalid cursor");
        }
    }

    private static long micros(OffsetDateTime updatedAt) {
        Instant instant = updatedAt.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000L;
    }
}
