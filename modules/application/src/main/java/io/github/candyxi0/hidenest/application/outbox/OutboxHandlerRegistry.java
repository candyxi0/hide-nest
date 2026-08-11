package io.github.candyxi0.hidenest.application.outbox;

import java.util.List;

/**
 * Handler registry: fail-closed semantics (R1-04).
 * Missing/ambiguous is a typed resolution result, not an OutboxProcessingException.
 */
public class OutboxHandlerRegistry {

    public enum Resolution { EXACTLY_ONE, MISSING, AMBIGUOUS }

    private final List<OutboxEffectHandler> handlers;

    public OutboxHandlerRegistry(List<OutboxEffectHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public record Resolved(Resolution resolution, OutboxEffectHandler handler) {}

    public Resolved resolve(String eventType) {
        List<OutboxEffectHandler> matching = handlers.stream()
                .filter(h -> h.supports(eventType))
                .toList();
        if (matching.isEmpty()) return new Resolved(Resolution.MISSING, null);
        if (matching.size() > 1) return new Resolved(Resolution.AMBIGUOUS, null);
        return new Resolved(Resolution.EXACTLY_ONE, matching.get(0));
    }

    public boolean isEmpty() { return handlers.isEmpty(); }
}
