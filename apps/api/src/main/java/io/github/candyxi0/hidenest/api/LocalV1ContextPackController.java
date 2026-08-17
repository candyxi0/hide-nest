package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackResult;
import io.github.candyxi0.hidenest.contracts.model.ContextPackResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loopback-only, bearer-gated context pack retrieval entry for Local V1. */
@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1ContextPackController {

    private final LocalV1ContextPackCoordinator coordinator;

    public LocalV1ContextPackController(LocalV1ContextPackCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/v1/context-packs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ContextPackResponse create(@RequestBody JsonNode body, HttpServletRequest request) {
        LocalV1ContextPackRequest mapped = LocalV1ContextPackRequestMapper.map(body);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        LocalV1ContextPackResult result = coordinator.create(mapped, idempotencyKey);
        return LocalV1ContextPackResponseMapper.map(result);
    }
}
