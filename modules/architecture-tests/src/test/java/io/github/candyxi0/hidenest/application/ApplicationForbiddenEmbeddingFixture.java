package io.github.candyxi0.hidenest.application;

import io.github.candyxi0.hidenest.embedding.EmbeddingAdapterModule;

/** Synthetic fixture that violates the formal application boundary rule. */
public final class ApplicationForbiddenEmbeddingFixture {

    private final EmbeddingAdapterModule embeddingAdapter;

    public ApplicationForbiddenEmbeddingFixture(EmbeddingAdapterModule embeddingAdapter) {
        this.embeddingAdapter = embeddingAdapter;
    }

    public EmbeddingAdapterModule embeddingAdapter() {
        return embeddingAdapter;
    }
}
