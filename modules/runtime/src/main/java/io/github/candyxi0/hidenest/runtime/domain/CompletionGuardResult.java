package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Sealed type for completion guard results (R1-04).
 * Stale and Denied are restricted to specific existing failure codes.
 */
public sealed interface CompletionGuardResult
        permits CompletionGuardResult.Pass,
                CompletionGuardResult.Stale,
                CompletionGuardResult.Denied {

    Set<String> ALLOWED_STALE_CODES = Set.of("EXPECTED_REVISION_STALE", "POLICY_REVISION_STALE");
    Set<String> ALLOWED_DENIED_CODES = Set.of(
            "ACCESS_DENIED", "DERIVATION_BLOCKED", "DELETION_FENCED", "DATABASE_UNAVAILABLE");

    record Pass() implements CompletionGuardResult {}

    record Stale(String failureCode) implements CompletionGuardResult {
        public Stale {
            Objects.requireNonNull(failureCode, "failureCode must not be null for Stale");
            if (!ALLOWED_STALE_CODES.contains(failureCode)) {
                throw new IllegalArgumentException(
                        "Stale failureCode must be one of " + ALLOWED_STALE_CODES + ", got " + failureCode);
            }
        }
    }

    record Denied(String failureCode) implements CompletionGuardResult {
        public Denied {
            Objects.requireNonNull(failureCode, "failureCode must not be null for Denied");
            if (!ALLOWED_DENIED_CODES.contains(failureCode)) {
                throw new IllegalArgumentException(
                        "Denied failureCode must be one of " + ALLOWED_DENIED_CODES + ", got " + failureCode);
            }
        }
    }
}
