package io.github.candyxi0.hidenest.application.outbox;

import io.github.candyxi0.hidenest.application.outbox.OutboxHandlerRegistry.Resolved;
import io.github.candyxi0.hidenest.application.outbox.OutboxHandlerRegistry.Resolution;
import io.github.candyxi0.hidenest.runtime.domain.ClaimedOutboxEvent;
import io.github.candyxi0.hidenest.runtime.domain.CompletionGuardResult;
import io.github.candyxi0.hidenest.runtime.domain.OutboxFailureSettlement;
import io.github.candyxi0.hidenest.runtime.domain.OutboxSuccessOutcome;
import io.github.candyxi0.hidenest.runtime.domain.OutboxTerminalSettlement;
import io.github.candyxi0.hidenest.runtime.port.CompletionGuardPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OutboxWorkerCoordinator {

    private final RuntimeTransactionPort txPort;
    private final CompletionGuardPort guardPort;
    private final OutboxHandlerRegistry handlerRegistry;
    private final BackoffCalculator backoff;
    private final Clock clock;

    public OutboxWorkerCoordinator(
            RuntimeTransactionPort txPort, CompletionGuardPort guardPort,
            OutboxHandlerRegistry handlerRegistry, BackoffCalculator backoff, Clock clock) {
        this.txPort = txPort;
        this.guardPort = guardPort;
        this.handlerRegistry = handlerRegistry;
        this.backoff = backoff;
        this.clock = clock;
    }

    // ── R1A-01: unified count accumulator ──────────────────────────────────────

    static final class Counts {
        int settled, alreadySettled, retryScheduled, finalFailed, rejected, leaseLost;
        int handlerMissing, handlerAmbiguous;
    }

    /** Unified Terminal mapping: AlreadyTerminal → check real state. */
    static void mapTerminal(OutboxTerminalSettlement ts, Counts c) {
        switch (ts) {
            case OutboxTerminalSettlement.Rejected __ -> c.rejected++;
            case OutboxTerminalSettlement.AlreadyTerminal at -> {
                switch (at.state()) {
                    case "SUCCEEDED"    -> c.alreadySettled++;
                    case "FINAL_FAILED" -> c.finalFailed++;
                    default             -> c.rejected++;
                }
            }
            case OutboxTerminalSettlement.LeaseLost __ -> c.leaseLost++;
        }
    }

    /** Unified Failure mapping: Terminal → check real state. */
    static void mapFailure(OutboxFailureSettlement fs, Counts c) {
        switch (fs) {
            case OutboxFailureSettlement.RetryScheduled __ -> c.retryScheduled++;
            case OutboxFailureSettlement.FinalFailed __    -> c.finalFailed++;
            case OutboxFailureSettlement.Terminal t -> {
                switch (t.state()) {
                    case "SUCCEEDED"    -> c.alreadySettled++;
                    case "FINAL_FAILED" -> c.finalFailed++;
                    default             -> c.rejected++;
                }
            }
            case OutboxFailureSettlement.LeaseLost __ -> c.leaseLost++;
        }
    }

    /** Unified Success mapping. */
    static void mapSuccess(OutboxSuccessOutcome o, Counts c) {
        switch (o) {
            case SETTLED          -> c.settled++;
            case ALREADY_SETTLED  -> c.alreadySettled++;
            case LEASE_LOST       -> c.leaseLost++;
        }
    }

    // ── runOnce ────────────────────────────────────────────────────────────────

    public OutboxBatchResult runOnce(String leaseOwner, Duration leaseDuration, int batchSize) {
        if (leaseOwner == null || leaseOwner.isBlank())
            throw new IllegalArgumentException("leaseOwner must not be blank");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero())
            throw new IllegalArgumentException("leaseDuration must be positive");
        if (batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("batchSize must be 1-100");

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents(
                leaseOwner, now, now.plus(leaseDuration), batchSize);
        if (claimed.isEmpty()) return OutboxBatchResult.empty();

        Counts c = new Counts();
        List<UUID> ids = new ArrayList<>();

        for (ClaimedOutboxEvent event : claimed) {
            ids.add(event.eventId());

            // 1. Guard
            CompletionGuardResult guard = guardPort.check(
                    event.eventId(), event.aggregateKind(), event.aggregateId(),
                    event.aggregateRevision(), event.purpose());
            if (guard instanceof CompletionGuardResult.Stale s) {
                mapTerminal(txPort.settleOutboxRejected(
                        event.eventId(), leaseOwner, OffsetDateTime.now(clock), s.failureCode()), c);
                continue;
            }
            if (guard instanceof CompletionGuardResult.Denied d) {
                mapTerminal(txPort.settleOutboxRejected(
                        event.eventId(), leaseOwner, OffsetDateTime.now(clock), d.failureCode()), c);
                continue;
            }

            // 2. Handler resolution
            Resolved resolved = handlerRegistry.resolve(event.eventType());
            if (resolved.resolution() == Resolution.MISSING) {
                c.handlerMissing++;
                mapTerminal(txPort.settleOutboxRejected(
                        event.eventId(), leaseOwner, OffsetDateTime.now(clock), "INTERNAL_FAILURE"), c);
                continue;
            }
            if (resolved.resolution() == Resolution.AMBIGUOUS) {
                c.handlerAmbiguous++;
                mapTerminal(txPort.settleOutboxRejected(
                        event.eventId(), leaseOwner, OffsetDateTime.now(clock), "INTERNAL_FAILURE"), c);
                continue;
            }

            // 3. Execute handler
            OutboxEffectHandler handler = resolved.handler();
            try {
                ProcessedEffect effect = handler.process(event);
                mapSuccess(txPort.settleOutboxSuccess(
                        event.eventId(), leaseOwner,
                        effect.consumerCode(), effect.effectKey(),
                        OffsetDateTime.now(clock)), c);
            } catch (OutboxProcessingException ope) {
                OffsetDateTime nextAt = (event.attemptCount() >= 7)
                        ? OffsetDateTime.now(clock)
                        : backoff.nextAvailableAt(event.attemptCount());
                mapFailure(txPort.settleOutboxFailure(
                        event.eventId(), leaseOwner, nextAt,
                        OffsetDateTime.now(clock), ope.failureCode()), c);
            } catch (Exception e) {
                OffsetDateTime nextAt = (event.attemptCount() >= 7)
                        ? OffsetDateTime.now(clock)
                        : backoff.nextAvailableAt(event.attemptCount());
                mapFailure(txPort.settleOutboxFailure(
                        event.eventId(), leaseOwner, nextAt,
                        OffsetDateTime.now(clock), "INTERNAL_FAILURE"), c);
            }
        }

        return new OutboxBatchResult(claimed.size(),
                c.settled, c.alreadySettled, c.retryScheduled,
                c.finalFailed, c.rejected, c.leaseLost,
                c.handlerMissing, c.handlerAmbiguous, ids);
    }
}
