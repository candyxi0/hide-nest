package io.github.candyxi0.hidenest.application.coordinator;

/**
 * Fail-closed exception for the Local V1 closeout write vertical.
 *
 * <p>Every {@link Code} value maps to an existing value in the frozen 50-code registry; this enum
 * is an application-level classification, not a new formal failure code.</p>
 */
public class LocalV1CloseoutException extends RuntimeException {

    public enum Code {
        IDEMPOTENCY_KEY_REQUIRED,
        IDEMPOTENCY_KEY_REUSED,
        REQUEST_SCHEMA_INVALID,
        USER_CONFIRMATION_PROOF_INVALID,
        SOURCE_RANGE_GAP,
        SOURCE_ORDER_INVALID,
        REVIEW_SESSION_NOT_OPEN,
        REVIEW_MEMBER_MISMATCH,
        PROPOSAL_CONFLICT,
        CANONICAL_COMMIT_FAILED,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1CloseoutException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1CloseoutException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
