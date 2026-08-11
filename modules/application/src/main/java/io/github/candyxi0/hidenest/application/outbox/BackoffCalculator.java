package io.github.candyxi0.hidenest.application.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.random.RandomGenerator;

/**
 * HDM006-D04: exponential backoff with jitter.
 * Pure Java, injectable Clock and RandomGenerator for testability.
 */
public class BackoffCalculator {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(10);
    private static final double JITTER_FACTOR = 0.25;

    private final Clock clock;
    private final RandomGenerator random;

    public BackoffCalculator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * Compute next available_at for a retry.
     * @param currentAttempt must be 0-6 (new attempt will be 1-7)
     * @return next available_at with backoff + jitter applied
     */
    public OffsetDateTime nextAvailableAt(int currentAttempt) {
        if (currentAttempt < 0 || currentAttempt > 6) {
            throw new IllegalArgumentException("currentAttempt must be 0-6, got " + currentAttempt);
        }
        int newAttempt = currentAttempt + 1;
        long baseMs = BASE.toMillis();
        long maxMs = MAX.toMillis();
        long delayMs = Math.min(baseMs * (1L << (newAttempt - 1)), maxMs);
        long jitterRange = (long) (delayMs * JITTER_FACTOR);
        long jitter = random.nextLong(-jitterRange, jitterRange + 1);
        long finalDelay = Math.max(0, delayMs + jitter);
        return OffsetDateTime.now(clock).plusNanos(finalDelay * 1_000_000);
    }
}
