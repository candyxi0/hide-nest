package io.github.candyxi0.hidenest.application.coordinator;

public final class LocalV1S3C2Exception extends RuntimeException {

    private final String failureCode;

    public LocalV1S3C2Exception(String failureCode) {
        super(failureCode);
        this.failureCode = failureCode;
    }

    public LocalV1S3C2Exception(String failureCode, Throwable cause) {
        super(failureCode, cause);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
