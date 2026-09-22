package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.FormationAttentionNotice;
import io.github.candyxi0.hidenest.runtime.domain.FormationAttempt;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Claim, lease and settle a persisted Formation task without retaining its payload. */
public interface FormationControlPort {
    Optional<FormationAttempt> claim(String ownerRef, Duration lease);

    boolean renew(UUID attemptId, String ownerRef, Duration lease);

    FormationSettlementOutcome settle(
            FormationSettlementCandidate candidate,
            FormationHardCheckPort hardChecks,
            CanonicalCommitPort canonicalCommit,
            FormationStopPort stopPort);

    FormationSettlementOutcome fail(UUID attemptId, String failureCode);

    List<FormationAttentionNotice> attentionNotices(int limit);
}
