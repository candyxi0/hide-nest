package io.github.candyxi0.hidenest.arch.canary;

/** Canary file containing a forbidden body text field name to prove the scanner detects violations. */
public final class BodyTextFieldCanary {

    private BodyTextFieldCanary() {}

    /** This field name must be detected by the body text leak scanner. */
    @SuppressWarnings("unused")
    private String bodyText;
}
