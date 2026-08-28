package io.github.candyxi0.hidenest.application.coordinator;

/** Stable Bubble failure classification. No user text is carried in messages. */
public final class LocalV1BubbleException extends RuntimeException {

    public enum Code {
        REQUEST_SCHEMA_INVALID,
        SPACE_KEY_MISMATCH,
        TURN_KEY_REUSED,
        EMBEDDING_UNAVAILABLE,
        BUBBLE_STALE,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1BubbleException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1BubbleException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
