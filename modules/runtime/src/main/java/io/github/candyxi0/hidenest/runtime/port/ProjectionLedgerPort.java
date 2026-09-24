package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Attention;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Claim;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Event;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Result;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Settlement;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Target;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Core-only short transactions around committed Outbox events; no receiver runs inside these calls. */
public interface ProjectionLedgerPort {
    void register(Target target);

    List<Event> discover(String worldRef, int projectionGeneration, int limit);

    Optional<Claim> claim(
            String worldRef,
            int projectionGeneration,
            UUID eventId,
            String ownerRef,
            Duration lease,
            String materialDigest);

    boolean renew(Claim claim, Duration lease);

    Settlement settle(Result result);

    List<Attention> attention(String worldRef, int projectionGeneration, int limit);

    boolean recover(String worldRef, int projectionGeneration, UUID eventId, String expectedState);
}
