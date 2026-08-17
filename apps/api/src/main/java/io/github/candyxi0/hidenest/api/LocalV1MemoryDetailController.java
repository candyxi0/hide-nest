package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.contracts.model.MemoryDetailResponse;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1MemoryDetailController {

    private final LocalV1S2BQueryCoordinator coordinator;

    public LocalV1MemoryDetailController(LocalV1S2BQueryCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping(value = "/v1/memories/{memoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    MemoryDetailResponse detail(
            @PathVariable("memoryId") UUID memoryId, HttpServletRequest request) {
        return new MemoryDetailResponse(
                LocalV1RequestContext.requestId(request),
                ResultCategory.SUCCEEDED,
                LocalV1MemoryResponseMapper.detail(coordinator.getMemoryDetail(memoryId)));
    }
}
