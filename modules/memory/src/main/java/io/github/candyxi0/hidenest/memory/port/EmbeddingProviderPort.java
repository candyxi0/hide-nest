package io.github.candyxi0.hidenest.memory.port;

import java.util.List;
import java.util.Objects;

/** Embedding provider port. It exposes no database, HTTP, or adapter types. */
public interface EmbeddingProviderPort {

    /** Probe the remote model service and return a minimal, non-vector snapshot. */
    EmbeddingHealth health();

    /**
     * Convert texts to vectors. Implementations must fail closed on any transport or
     * protocol violation rather than returning partial or unvalidated data.
     */
    EmbeddingResult embed(List<String> texts);

    /** Minimal health snapshot. Never carries body or vector content. */
    record EmbeddingHealth(boolean healthy, String model, int dimension) {
        public EmbeddingHealth {
            model = Objects.requireNonNull(model, "model");
            if (dimension <= 0) {
                throw new IllegalArgumentException("dimension must be positive");
            }
        }
    }

    /** Raw embedding response: model identity plus one vector per input text. */
    record EmbeddingResult(String model, int dimension, List<double[]> vectors) {
        public EmbeddingResult {
            model = Objects.requireNonNull(model, "model");
            if (dimension <= 0) {
                throw new IllegalArgumentException("dimension must be positive");
            }
            vectors = Objects.requireNonNull(vectors, "vectors").stream()
                    .map(double[]::clone)
                    .toList();
        }

        @Override
        public List<double[]> vectors() {
            return vectors.stream().map(double[]::clone).toList();
        }
    }
}
