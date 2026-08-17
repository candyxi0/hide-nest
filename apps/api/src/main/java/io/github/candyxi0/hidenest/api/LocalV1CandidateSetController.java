package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCloseoutCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetException;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetCloseoutResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetCloseoutResult.CandidateCloseoutItem;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.contracts.model.CandidateAction;
import io.github.candyxi0.hidenest.contracts.model.CandidateDisposition;
import io.github.candyxi0.hidenest.contracts.model.CandidateSetProjectionItem;
import io.github.candyxi0.hidenest.contracts.model.CandidateSetProjectionPhase;
import io.github.candyxi0.hidenest.contracts.model.CandidateSetSubmissionPhase;
import io.github.candyxi0.hidenest.contracts.model.CandidateSetSubmissionResponse;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loopback-only, bearer-gated CandidateSet final submission entry for Local V1. */
@RestController
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1CandidateSetController {

    private final LocalV1CandidateSetCloseoutCoordinator coordinator;

    public LocalV1CandidateSetController(LocalV1CandidateSetCloseoutCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/v1/review-sessions/{id}/final-submissions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CandidateSetSubmissionResponse> submitReviewFinal(
            @PathVariable("id") UUID pathReviewSessionId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        LocalV1CandidateSetRequest appRequest = LocalV1CandidateSetRequestMapper.map(body);

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!idempotencyKey.equals(appRequest.candidateSetId().toString())) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }

        LocalV1CandidateSetCloseoutResult result = coordinator.submit(appRequest, pathReviewSessionId);

        List<CandidateSetProjectionItem> items = new ArrayList<>();
        for (CandidateCloseoutItem item : result.candidates()) {
            CandidateSetProjectionItem apiItem = new CandidateSetProjectionItem(
                    item.candidateId(),
                    item.ordinal(),
                    CandidateDisposition.fromValue(item.disposition()),
                    CandidateAction.fromValue(item.action()),
                    CandidateSetProjectionPhase.fromValue(item.phase()));
            if (item.memoryId() != null) {
                apiItem.setMemoryId(item.memoryId());
            }
            if (item.memoryRevisionId() != null) {
                apiItem.setMemoryRevisionId(item.memoryRevisionId());
            }
            if (item.revisionNo() != null) {
                apiItem.setRevisionNo(item.revisionNo());
            }
            items.add(apiItem);
        }

        CandidateSetSubmissionResponse response = new CandidateSetSubmissionResponse(
                LocalV1RequestContext.requestId(request),
                ResultCategory.fromValue(result.resultCategory()),
                result.candidateSetId(),
                CandidateSetSubmissionPhase.fromValue(result.phase()),
                items);
        if (result.reviewSessionId() != null) {
            response.setReviewSessionId(result.reviewSessionId());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
