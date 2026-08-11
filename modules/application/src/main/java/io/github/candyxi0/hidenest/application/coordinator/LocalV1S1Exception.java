package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;

public class LocalV1S1Exception extends RuntimeException {

    private final CanonicalFailureCode failureCode;

    public LocalV1S1Exception(CanonicalFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }

    public LocalV1S1Exception(CanonicalFailureCode failureCode, Throwable cause) {
        super(failureCode.name(), cause);
        this.failureCode = failureCode;
    }

    public CanonicalFailureCode failureCode() {
        return failureCode;
    }
}
