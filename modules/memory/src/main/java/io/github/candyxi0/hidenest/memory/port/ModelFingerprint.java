package io.github.candyxi0.hidenest.memory.port;

import java.util.Objects;

/** Immutable model fingerprint that pins a vector to one embedding artifact. */
public record ModelFingerprint(String modelName, byte[] ggufSha256, int dimension, String normalization) {

    public ModelFingerprint {
        modelName = Objects.requireNonNull(modelName, "modelName");
        ggufSha256 = Objects.requireNonNull(ggufSha256, "ggufSha256").clone();
        normalization = Objects.requireNonNull(normalization, "normalization");
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
    }

    @Override
    public byte[] ggufSha256() {
        return ggufSha256.clone();
    }
}
