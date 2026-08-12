package io.github.candyxi0.hidenest.application.coordinator;

/** Internal fail-closed classification for the local S2B read path. */
public class LocalV1S2BException extends RuntimeException {

    public enum Code {
        INVALID_ARGUMENT,
        NOT_FOUND,
        DELETION_FENCED,
        CURRENT_POINTER_INVALID,
        OWNER_BINDING_INVALID,
        EVIDENCE_RELATION_INVALID,
        ANCHOR_INVALID,
        SOURCE_UNIT_INVALID,
        ACTOR_INVALID,
        PAYLOAD_INVALID,
        PAYLOAD_UNAVAILABLE,
        UTF8_INVALID,
        EVIDENCE_LIMIT_EXCEEDED
    }

    private final Code code;

    public LocalV1S2BException(Code code) {
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
