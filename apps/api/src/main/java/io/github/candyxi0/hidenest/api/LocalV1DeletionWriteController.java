package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionWriteCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1DeletionEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1DeletionPreviewResult;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.application.model.LocalV1SharedMemoryReference;
import io.github.candyxi0.hidenest.contracts.model.AsyncAcceptedResponse;
import io.github.candyxi0.hidenest.contracts.model.DeletionClosureMember;
import io.github.candyxi0.hidenest.contracts.model.DeletionEvidenceItem;
import io.github.candyxi0.hidenest.contracts.model.DeletionPreviewResponse;
import io.github.candyxi0.hidenest.contracts.model.DeletionSharedMemory;
import io.github.candyxi0.hidenest.contracts.model.FailureCode;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import io.github.candyxi0.hidenest.contracts.model.RunPhase;
import io.github.candyxi0.hidenest.contracts.model.RunStatusResponse;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loopback-only, capability-gated permanent-deletion write entry for Local V1. */
@RestController
@Profile("local-v1-synthetic")
public final class LocalV1DeletionWriteController {

    private final LocalV1DeletionWriteCoordinator coordinator;

    public LocalV1DeletionWriteController(LocalV1DeletionWriteCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/v1/deletion-previews",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DeletionPreviewResponse> createPreview(@RequestBody JsonNode body, HttpServletRequest request) {
        LocalV1DeletionRequestMapper.PreviewInput input = LocalV1DeletionRequestMapper.mapPreview(body);
        LocalV1DeletionPreviewResult result = coordinator.preview(
                input.targetId(),
                input.expectedRevision(),
                input.expectedPolicyRevision(),
                input.requestManifestHash(),
                request.getHeader("Idempotency-Key"));

        List<DeletionClosureMember> members =
                result.closureMembers().stream().map(LocalV1DeletionWriteController::toMember).toList();
        List<DeletionEvidenceItem> evidence =
                result.evidence().stream().map(LocalV1DeletionWriteController::toEvidenceItem).toList();
        List<DeletionSharedMemory> sharedMemories =
                result.sharedMemories().stream().map(LocalV1DeletionWriteController::toSharedMemory).toList();
        DeletionPreviewResponse response = new DeletionPreviewResponse(
                LocalV1RequestContext.requestId(request),
                ResultCategory.SUCCEEDED,
                result.previewId(),
                Math.toIntExact(result.previewRevision()),
                LocalV1DeletionCanonicalizer.bytesToHex(result.manifestHash()),
                members,
                evidence,
                sharedMemories);
        return ResponseEntity.ok(response);
    }

    @PostMapping(
            value = "/v1/deletion-previews/{id}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AsyncAcceptedResponse> confirm(
            @PathVariable("id") UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        LocalV1DeletionRequestMapper.ConfirmInput input = LocalV1DeletionRequestMapper.mapConfirm(body);
        LocalV1RunStatus status = coordinator.confirm(
                id,
                input.targetId(),
                input.expectedRevision(),
                input.expectedPolicyRevision(),
                input.requestManifestHash(),
                input.previewId(),
                input.previewRevision(),
                input.manifestHash(),
                request.getHeader("Idempotency-Key"));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AsyncAcceptedResponse(
                        LocalV1RequestContext.requestId(request),
                        ResultCategory.SUCCEEDED,
                        status.runId(),
                        URI.create("/v1/deletion-runs/" + status.runId()),
                        RunPhase.valueOf(status.phase())));
    }

    @GetMapping(value = "/v1/deletion-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
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

    private static DeletionClosureMember toMember(DeletionPreviewPort.Member member) {
        DeletionClosureMember model = new DeletionClosureMember(
                Math.toIntExact(member.ordinal()), member.memberKind(), member.targetId(), member.disposition());
        if (member.targetRevisionRef() != null) {
            model.targetRevisionRef(Math.toIntExact(member.targetRevisionRef()));
        }
        if (member.sizeBytes() != null) {
            model.sizeBytes(Math.toIntExact(member.sizeBytes()));
        }
        if (member.contentHash() != null) {
            model.contentHash(LocalV1DeletionCanonicalizer.bytesToHex(member.contentHash()));
        }
        return model;
    }

    private static DeletionEvidenceItem toEvidenceItem(LocalV1DeletionEvidenceMessage message) {
        return new DeletionEvidenceItem(
                message.anchorId(), Math.toIntExact(message.ordinal()), message.actorId(), message.actorStableRef(),
                message.displayLabel(), message.occurredAt(), message.bodyText(),
                message.sharedByMemoryIds());
    }

    private static DeletionSharedMemory toSharedMemory(LocalV1SharedMemoryReference reference) {
        return new DeletionSharedMemory(reference.memoryId(), Math.toIntExact(reference.revisionNo()), reference.title());
    }
}
