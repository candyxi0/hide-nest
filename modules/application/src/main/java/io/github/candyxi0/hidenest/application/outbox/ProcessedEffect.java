package io.github.candyxi0.hidenest.application.outbox;

import java.util.Objects;

/**
 * Carrier for handler-produced consumer identity and effect key (R1-07).
 * effectKey: ASCII-safe opaque key, 1-256 chars, no body/JSON/newlines.
 */
public record ProcessedEffect(String consumerCode, String effectKey) {

    private static final String CONSUMER_CODE_PATTERN = "^[A-Z][A-Z0-9_]{0,63}$";
    private static final String EFFECT_KEY_PATTERN = "^[A-Za-z0-9._:/-]{1,256}$";

    public ProcessedEffect {
        Objects.requireNonNull(consumerCode, "consumerCode must not be null");
        Objects.requireNonNull(effectKey, "effectKey must not be null");
        if (!consumerCode.matches(CONSUMER_CODE_PATTERN)) {
            throw new IllegalArgumentException("consumerCode format invalid");
        }
        if (!effectKey.matches(EFFECT_KEY_PATTERN)) {
            throw new IllegalArgumentException("effectKey format invalid");
        }
    }

    /** Body-free toString — no consumerCode/effectKey values in output. */
    @Override
    public String toString() {
        return "ProcessedEffect[consumerCode=<redacted>,effectKey=<redacted>]";
    }
}
