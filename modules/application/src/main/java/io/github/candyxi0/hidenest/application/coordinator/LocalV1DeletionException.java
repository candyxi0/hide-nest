package io.github.candyxi0.hidenest.application.coordinator;

/**
 * Fail-closed exception for the Local V1 permanent-deletion write vertical.
 *
 * <p>Every {@link Code} value maps to an existing value in the frozen 50-code registry; this enum
 * is an application-level classification, not a new formal failure code.</p>
 */
public final class LocalV1DeletionException extends RuntimeException {

    public enum Code {
        IDEMPOTENCY_KEY_REQUIRED,
        IDEMPOTENCY_KEY_REUSED,
        REQUEST_SCHEMA_INVALID,
        NOT_FOUND,
        DELETION_FENCED,
        DELETION_PREVIEW_STALE,
        DELETION_CLOSURE_MISMATCH,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1DeletionException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1DeletionException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
