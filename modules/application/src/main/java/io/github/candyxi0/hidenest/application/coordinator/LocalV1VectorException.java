package io.github.candyxi0.hidenest.application.coordinator;

/** Internal fail-closed classification for the Local V1 embedding vector path. */
public class LocalV1VectorException extends RuntimeException {

    public enum Code {
        INVALID_ARGUMENT,
        NOT_FOUND,
        NOT_ACTIVE,
        CURRENT_POINTER_INVALID,
        CURRENT_POINTER_CHANGED,
        OWNER_BINDING_INVALID,
        BODY_INVALID,
        BODY_HASH_CHANGED,
        EMBEDDING_UNAVAILABLE,
        EMBEDDING_RESPONSE_INVALID,
        VECTOR_CONFLICT
    }

    private final Code code;

    public LocalV1VectorException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public Code failureCode() {
        return code;
    }
}
