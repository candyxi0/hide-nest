package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Body-free binding that lets a later Formation task reopen its bounded source material. */
public record SourceReadBinding(
        Kind kind,
        String reference,
        String version,
        OffsetDateTime expiresAt) {

    public enum Kind {
        STABLE_REREAD,
        BOUNDED_SNAPSHOT,
        UNAVAILABLE
    }

    public SourceReadBinding {
        Objects.requireNonNull(kind, "kind must not be null");
        switch (kind) {
            case STABLE_REREAD -> {
                reference = requireText(reference, "reference", 512);
                version = requireText(version, "version", 128);
                if (expiresAt != null) {
                    throw new IllegalArgumentException("stable reread must not expire");
                }
            }
            case BOUNDED_SNAPSHOT -> {
                reference = requireText(reference, "reference", 512);
                version = requireText(version, "version", 128);
                Objects.requireNonNull(expiresAt, "bounded snapshot must expire");
            }
            case UNAVAILABLE -> {
                if (reference != null || version != null || expiresAt != null) {
                    throw new IllegalArgumentException("unavailable binding must not carry a reference");
                }
            }
        }
    }

    public boolean isReadableAt(OffsetDateTime instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return kind == Kind.STABLE_REREAD
                || (kind == Kind.BOUNDED_SNAPSHOT && expiresAt.isAfter(instant));
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}
