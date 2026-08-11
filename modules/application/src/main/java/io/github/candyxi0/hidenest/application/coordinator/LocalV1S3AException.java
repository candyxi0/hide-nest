package io.github.candyxi0.hidenest.application.coordinator;

/** Fail-closed local S3A classification; none of these are formal runtime failure codes. */
public final class LocalV1S3AException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        PREVIEW_STALE,
        CURRENT_POINTER_INVALID,
        GRAPH_INVALID,
        GRAPH_LIMIT_EXCEEDED,
        PAYLOAD_INVALID,
        PERSISTENCE_CONFLICT
    }

    private final Code code;

    public LocalV1S3AException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
