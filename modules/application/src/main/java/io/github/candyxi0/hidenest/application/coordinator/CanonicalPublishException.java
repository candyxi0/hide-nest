package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;

public class CanonicalPublishException extends RuntimeException {

    private final CanonicalFailureCode failureCode;

    public CanonicalPublishException(CanonicalFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }

    public CanonicalFailureCode failureCode() {
        return failureCode;
    }
}
