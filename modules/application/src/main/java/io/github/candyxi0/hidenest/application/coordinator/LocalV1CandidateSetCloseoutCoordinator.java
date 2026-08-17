package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetCloseoutResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetCloseoutResult.CandidateCloseoutItem;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetProjectionResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetProjectionResult.CandidateProjection;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetResult.CandidateOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local V1 CandidateSet HTTP closeout facade.
 *
 * <p>Orchestrates the batch decision commit and the CREATE-only projection; never duplicates
 * Task36A/Task36B1 business logic. The controller must call this facade, not the batch and
 * projection coordinators directly.</p>
 */
public class LocalV1CandidateSetCloseoutCoordinator {

    private static final String NO_CANDIDATES = "NO_CANDIDATES";
    private static final String DECISIONS_COMMITTED = "DECISIONS_COMMITTED";
    private static final String CANONICAL_COMMITTED = "CANONICAL_COMMITTED";
    private static final String INDEX_READY = "INDEX_READY";
    private static final String REJECTED = "REJECTED";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String CREATE = "CREATE";
    private static final String NO_RELEVANT_RESULT = "NO_RELEVANT_RESULT";
    private static final String SUCCEEDED = "SUCCEEDED";

    private final LocalV1CandidateSetBatchCoordinator batch;
    private final LocalV1CandidateSetCreateProjectionCoordinator projection;

    public LocalV1CandidateSetCloseoutCoordinator(
            LocalV1CandidateSetBatchCoordinator batch,
            LocalV1CandidateSetCreateProjectionCoordinator projection) {
        this.batch = batch;
        this.projection = projection;
    }

    /**
     * Submit a CandidateSet through the full closeout pipeline.
     *
     * <p>Path {@code reviewSessionId} is validated against the canonical derivation before any
     * database write. Replay is monotonic: CANONICAL_COMMITTED may converge to INDEX_READY but
     * never regress.</p>
     */
    public LocalV1CandidateSetCloseoutResult submit(
            LocalV1CandidateSetRequest request, UUID pathReviewSessionId) {
        // Path identity validation before any write.
        UUID canonicalReviewSessionId =
                LocalV1CandidateSetCanonicalizer.reviewSessionId(request.candidateSetId());
        if (!canonicalReviewSessionId.equals(pathReviewSessionId)) {
            throw new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
        }

        LocalV1CandidateSetResult batchResult = batch.submit(request);

        // Empty candidates.
        if (NO_CANDIDATES.equals(batchResult.status())) {
            return new LocalV1CandidateSetCloseoutResult(
                    batchResult.candidateSetId(), null, NO_CANDIDATES, SUCCEEDED, List.of());
        }

        // Classify accepted candidates.
        List<CandidateOutcome> acceptedCreates = new ArrayList<>();
        boolean hasAcceptedNonCreate = false;
        boolean hasAnyAccepted = false;
        for (CandidateOutcome outcome : batchResult.outcomes()) {
            if (ACCEPTED.equals(outcome.disposition())) {
                hasAnyAccepted = true;
                if (CREATE.equals(outcome.action())) {
                    acceptedCreates.add(outcome);
                } else {
                    hasAcceptedNonCreate = true;
                }
            }
        }

        // All-rejected: zero publish, zero embedding.
        if (!hasAnyAccepted) {
            List<CandidateCloseoutItem> items = new ArrayList<>();
            for (CandidateOutcome outcome : batchResult.outcomes()) {
                items.add(new CandidateCloseoutItem(
                        outcome.candidateId(), outcome.ordinal(), outcome.disposition(),
                        outcome.action(), REJECTED, null, null, null));
            }
            return new LocalV1CandidateSetCloseoutResult(
                    batchResult.candidateSetId(), batchResult.reviewSessionId(),
                    DECISIONS_COMMITTED, NO_RELEVANT_RESULT, items);
        }

        // Accepted REVISE/SUPERSEDE present: no projection this round.
        if (hasAcceptedNonCreate) {
            List<CandidateCloseoutItem> items = new ArrayList<>();
            for (CandidateOutcome outcome : batchResult.outcomes()) {
                String phase = ACCEPTED.equals(outcome.disposition()) ? DECISIONS_COMMITTED : REJECTED;
                items.add(new CandidateCloseoutItem(
                        outcome.candidateId(), outcome.ordinal(), outcome.disposition(),
                        outcome.action(), phase, null, null, null));
            }
            return new LocalV1CandidateSetCloseoutResult(
                    batchResult.candidateSetId(), batchResult.reviewSessionId(),
                    DECISIONS_COMMITTED, SUCCEEDED, items);
        }

        // All accepted are CREATE: project.
        LocalV1CandidateSetProjectionResult projectionResult = projection.project(batchResult.candidateSetId());

        List<CandidateCloseoutItem> items = new ArrayList<>();
        for (CandidateProjection p : projectionResult.candidates()) {
            items.add(new CandidateCloseoutItem(
                    p.candidateId(), p.ordinal(), p.disposition(), p.action(),
                    p.phase(), p.memoryId(), p.memoryRevisionId(), p.revisionNo()));
        }
        return new LocalV1CandidateSetCloseoutResult(
                batchResult.candidateSetId(), batchResult.reviewSessionId(),
                projectionResult.phase(), SUCCEEDED, items);
    }
}