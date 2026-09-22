package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Objects;

/** Opaque, monotonically ordered position in one registered source. */
public record SourceBoundary(long sequence, String cursor, String sourceVersion) {

    public SourceBoundary {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        cursor = requireText(cursor, "cursor", 512);
        sourceVersion = requireText(sourceVersion, "sourceVersion", 128);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}
