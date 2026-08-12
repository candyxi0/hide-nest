package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;

public final class LocalV1S3B2BException extends RuntimeException {

    private final CanonicalFailureCode failureCode;

    public LocalV1S3B2BException(CanonicalFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }

    public CanonicalFailureCode failureCode() {
        return failureCode;
    }
}
