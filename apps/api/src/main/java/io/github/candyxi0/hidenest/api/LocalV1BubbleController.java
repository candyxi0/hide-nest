package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1BubbleCoordinator;
import io.github.candyxi0.hidenest.contracts.model.BubbleResolveResponse;
import io.github.candyxi0.hidenest.contracts.model.BubbleRoomPurgeResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loopback-only, bearer-gated HTTP entry for platform-neutral Bubble Core. */
@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1BubbleController {

    private final LocalV1BubbleCoordinator coordinator;

    public LocalV1BubbleController(LocalV1BubbleCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/v1/bubbles/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BubbleResolveResponse resolve(@RequestBody JsonNode body) {
        return LocalV1BubbleResponseMapper.resolve(coordinator.resolve(LocalV1BubbleRequestMapper.resolve(body)));
    }

    @PostMapping(
            value = "/v1/bubbles/rooms/purge",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BubbleRoomPurgeResponse purge(@RequestBody JsonNode body) {
        return LocalV1BubbleResponseMapper.purge(coordinator.purge(LocalV1BubbleRequestMapper.purge(body)));
    }
}
