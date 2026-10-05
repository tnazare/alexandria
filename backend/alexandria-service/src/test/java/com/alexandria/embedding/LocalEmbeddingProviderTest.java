package com.alexandria.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link LocalEmbeddingProvider}: the default offline embedder.
 *
 * <p>Covers the two behaviors the task calls out explicitly — determinism (same input → same vector,
 * R8.1/R8.2) and that the produced vector dimension matches the configured dimension (R3.1/R3.5) —
 * plus the edge cases that keep those guarantees honest.</p>
 */
class LocalEmbeddingProviderTest {

    @Test
    void sameInputProducesIdenticalVector() {
        // R8.1/R8.2: deterministic — repeated embedding of identical text yields the same vector.
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(256);
        String text = "Deterministic hashing n-gram embedding of a skill description.";

        float[] first = provider.embed(text);
        float[] second = provider.embed(text);

        assertThat(second).containsExactly(first);
    }

    @Test
    void determinismHoldsAcrossSeparateProviderInstances() {
        // Determinism must not depend on instance state — a fresh instance embeds identically.
        String text = "cross-instance stability of the local embedder";

        float[] a = new LocalEmbeddingProvider(256).embed(text);
        float[] b = new LocalEmbeddingProvider(256).embed(text);

        assertThat(b).containsExactly(a);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 128, 256, 384, 512})
    void vectorLengthMatchesConfiguredDimension(int dimension) {
        // R3.1/R3.5: the vector length is exactly the configured dimension, whatever it is set to.
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(dimension);

        assertThat(provider.dimension()).isEqualTo(dimension);
        assertThat(provider.embed("some skill text")).hasSize(dimension);
    }

    @Test
    void blankAndNullTextYieldZeroVectorOfConfiguredDimension() {
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(256);

        assertThat(provider.embed(null)).hasSize(256).containsOnly(0f);
        assertThat(provider.embed("")).hasSize(256).containsOnly(0f);
        assertThat(provider.embed("   ")).hasSize(256).containsOnly(0f);
    }

    @Test
    void nonEmptyTextProducesUnitLengthVector() {
        // Vectors are L2-normalized so cosine distance (the HNSW index's ops) is well-behaved.
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(256);

        float[] vector = provider.embed("review a terraform module for drift");

        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        assertThat(Math.sqrt(sumSquares)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void differentTextProducesDifferentVector() {
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(256);

        float[] a = provider.embed("write integration tests in typescript with bun");
        float[] b = provider.embed("provision an rds instance with terraform");

        assertThat(b).isNotEqualTo(a);
    }

    @Test
    void modelIdIsStableAndNonBlank() {
        assertThat(new LocalEmbeddingProvider(256).modelId())
                .isEqualTo("local-hashing-ngram-v1");
    }

    @Test
    void nonPositiveDimensionIsRejected() {
        assertThatThrownBy(() -> new LocalEmbeddingProvider(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalEmbeddingProvider(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
