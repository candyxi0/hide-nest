package io.github.candyxi0.hidenest.application.outbox;

import io.github.candyxi0.hidenest.runtime.domain.ClaimedOutboxEvent;

/**
 * Business-agnostic handler for outbox events.
 * Only the port is defined here; zero production handlers exist in Slice D2.
 */
public interface OutboxEffectHandler {

    /** Returns true iff this handler can process the given event type. */
    boolean supports(String eventType);

    /**
     * Process a claimed event. The handler is responsible for its own
     * external idempotency. Must NOT return body/prompt/answer/思维链.
     */
    ProcessedEffect process(ClaimedOutboxEvent event) throws OutboxProcessingException;
}
