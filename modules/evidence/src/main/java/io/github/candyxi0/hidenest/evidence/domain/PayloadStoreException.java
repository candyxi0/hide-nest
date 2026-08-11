package io.github.candyxi0.hidenest.evidence.domain;

/** Vendor-neutral payload store exception. Error codes are store-level, not formal failure codes. */
public class PayloadStoreException extends RuntimeException {

    private final String errorCode;

    public PayloadStoreException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PayloadStoreException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
