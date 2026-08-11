package io.github.candyxi0.hidenest.application.outbox;

/**
 * Constrained exception for handler failures (R1-04).
 * Only accepts a fixed set of existing failure codes.
 * No cause chain, no original exception message, no parameters.
 */
public class OutboxProcessingException extends Exception {

    public enum Code {
        DATABASE_UNAVAILABLE,
        PAYLOAD_STORE_UNAVAILABLE,
        MODEL_PROVIDER_UNAVAILABLE,
        INTERNAL_FAILURE;

        public String failureCode() { return name(); }
    }

    private final Code code;

    public OutboxProcessingException(Code code) {
        super(code.name());
        this.code = code;
    }

    public String failureCode() { return code.failureCode(); }
    public Code code() { return code; }

    /** Suppress: never expose parameter values in message. */
    @Override public String getMessage() { return code.name(); }
    @Override public String toString() { return "OutboxProcessingException[" + code.name() + "]"; }
}
