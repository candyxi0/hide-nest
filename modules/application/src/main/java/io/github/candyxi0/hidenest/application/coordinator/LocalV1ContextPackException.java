package io.github.candyxi0.hidenest.application.coordinator;

/**
 * Fail-closed exception for the Local V1 context pack retrieval vertical.
 *
 * <p>Every {@link Code} value maps to an existing value in the frozen 50-code registry; this enum
 * is an application-level classification, not a new formal failure code.</p>
 */
public class LocalV1ContextPackException extends RuntimeException {

    public enum Code {
        IDEMPOTENCY_KEY_REQUIRED,
        IDEMPOTENCY_KEY_REUSED,
        REQUEST_SCHEMA_INVALID,
        EMBEDDING_UNAVAILABLE,
        CONTEXT_PACK_INVALIDATED,
        CONTEXT_PACK_EXPIRED,
        CONTEXT_PACK_STALE,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1ContextPackException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1ContextPackException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
