package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutVectorProjectionCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutReceipt;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.contracts.model.CloseoutReceipt;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import io.github.candyxi0.hidenest.contracts.model.RunPhase;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loopback-only, capability-gated closeout write entry for Local V1. */
@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1CloseoutWriteController {

    private final LocalV1CloseoutVectorProjectionCoordinator coordinator;

    public LocalV1CloseoutWriteController(LocalV1CloseoutVectorProjectionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/v1/closeout-submissions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CloseoutReceipt> submit(
            @RequestBody JsonNode body, HttpServletRequest request) {
        LocalV1CloseoutSubmission submission = LocalV1CloseoutRequestMapper.map(body);

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!idempotencyKey.equals(submission.submissionId().toString())) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.REQUEST_SCHEMA_INVALID);
        }

        LocalV1CloseoutReceipt receipt = coordinator.submit(submission);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CloseoutReceipt(
                        LocalV1RequestContext.requestId(request),
                        ResultCategory.SUCCEEDED,
                        receipt.runId(),
                        URI.create("/v1/runs/" + receipt.runId()),
                        RunPhase.valueOf(receipt.phase())));
    }
}
