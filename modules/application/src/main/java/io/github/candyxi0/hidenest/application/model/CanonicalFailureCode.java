package io.github.candyxi0.hidenest.application.model;

/**
 * Failure codes used by Slice C coordinators.
 * Every value here must exist in the frozen 50-code registry
 * (runtime.failure_code_registry table and OpenAPI spec).
 */
public enum CanonicalFailureCode {
    IDEMPOTENCY_KEY_REUSED,
    REVIEW_SESSION_NOT_OPEN,
    REVIEW_MEMBER_MISMATCH,
    PROPOSAL_CONFLICT,
    EXPECTED_REVISION_STALE,
    POLICY_REVISION_STALE,
    DERIVATION_BLOCKED,
    DELETION_FENCED,
    DELETION_PREVIEW_STALE,
    DELETION_CLOSURE_MISMATCH,
    CANONICAL_COMMIT_FAILED,
    INTERNAL_FAILURE
}
