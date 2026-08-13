package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.contracts.model.FailureCode;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import io.github.candyxi0.hidenest.contracts.model.RunPhase;
import io.github.candyxi0.hidenest.contracts.model.RunStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only run status read entry for Local V1. */
@RestController
@Profile("local-v1-synthetic")
public final class LocalV1RunStatusController {

    private final LocalV1CloseoutWriteCoordinator coordinator;

    public LocalV1RunStatusController(LocalV1CloseoutWriteCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping(value = "/v1/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    RunStatusResponse status(@PathVariable UUID runId, HttpServletRequest request) {
        LocalV1RunStatus status = coordinator.findRunStatus(runId);
        if (status == null) {
            throw new LocalV1NotFoundException();
        }
        RunStatusResponse response = new RunStatusResponse(
                        LocalV1RequestContext.requestId(request),
                        ResultCategory.SUCCEEDED,
                        status.runId(),
                        RunPhase.valueOf(status.phase()),
                        status.retryable())
                .startedAt(status.startedAt())
                .terminalAt(status.terminalAt());
        if (status.failureCode() != null) {
            response.failureCode(FailureCode.fromValue(status.failureCode()));
        }
        return response;
    }
}
