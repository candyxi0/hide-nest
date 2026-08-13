package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transport model for a single-candidate Local V1 closeout submission.
 *
 * <p>This mirrors the formal {@code CloseoutSubmissionRequest} contract as plain records so the
 * application boundary does not depend on the generated OpenAPI DTOs. The API controller maps the
 * generated DTO (or raw JSON) into this shape.</p>
 */
public record LocalV1CloseoutSubmission(
        UUID submissionId,
        UUID threadId,
        HideSelection hideSelection,
        UserConfirmation userConfirmation,
        List<SourceAnchor> sourceAnchors,
        ThreadReaderManifest threadReaderManifest,
        String confirmationProof) {

    public record HideSelection(
            UUID perspectiveActorId, String memoryType, String bodyText, String bodyHash) {}

    public record UserConfirmation(
            String decision, String reviewManifestHash, UUID confirmationSourceUnitId) {}

    public record SourceAnchor(UUID anchorId, List<AnchorUnit> units) {}

    public record AnchorUnit(UUID sourceUnitId, Long fromOffset, Long toOffset, Long ordinal) {}

    public record ThreadReaderManifest(
            String schemaVersion,
            Long fromOrdinal,
            Long toOrdinal,
            boolean continuous,
            String manifestHash,
            List<EvidenceMessage> selectedEvidenceMessages) {}

    public record EvidenceMessage(
            UUID sourceUnitId,
            UUID actorId,
            Long ordinal,
            String externalUnitRef,
            OffsetDateTime occurredAt,
            String bodyText,
            String bodyHash) {}
}
