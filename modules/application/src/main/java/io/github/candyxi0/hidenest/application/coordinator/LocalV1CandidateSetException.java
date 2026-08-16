package io.github.candyxi0.hidenest.application.coordinator;

/**
 * Fail-closed exception for the Local V1 multi-candidate closeout vertical.
 *
 * <p>Every {@link Code} value maps to an existing value in the frozen 50-code registry; this enum
 * is an application-level classification, not a new formal failure code.</p>
 */
public class LocalV1CandidateSetException extends RuntimeException {

    public enum Code {
        IDEMPOTENCY_KEY_REUSED,
        REQUEST_SCHEMA_INVALID,
        REVIEW_SESSION_NOT_OPEN,
        REVIEW_MEMBER_MISMATCH,
        PROPOSAL_CONFLICT,
        EXPECTED_REVISION_STALE,
        POLICY_REVISION_STALE,
        CANONICAL_COMMIT_FAILED,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1CandidateSetException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1CandidateSetException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
