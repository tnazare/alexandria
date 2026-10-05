package com.alexandria.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for {@link LocalEmbeddingProvider}.
 *
 * <p>Property: the local embedder is a deterministic, dimension-stable transformation — for any
 * input text and any configured positive dimension, (a) embedding the same text twice yields an
 * identical vector, and (b) the vector length equals the configured dimension.</p>
 *
 * <p>Validates: Requirements 3.1, 8.1</p>
 */
@Tag("Feature: semantic-search, Property 1: LocalEmbeddingProvider is deterministic and dimension-stable")
class LocalEmbeddingProviderDeterminismPropertyTest {

    /**
     * Property: same input text → identical vector, across arbitrary text.
     *
     * Validates: Requirements 8.1
     */
    @Property(tries = 200)
    void embeddingIsDeterministicForAnyText(@ForAll("anyText") String text) {
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(256);

        float[] first = provider.embed(text);
        float[] second = provider.embed(text);

        assertThat(second).containsExactly(first);
    }

    /**
     * Property: vector length always equals the configured dimension, for any positive dimension.
     *
     * Validates: Requirements 3.1
     */
    @Property(tries = 200)
    void vectorLengthAlwaysMatchesConfiguredDimension(
            @ForAll("anyText") String text, @ForAll @IntRange(min = 1, max = 1024) int dimension) {
        LocalEmbeddingProvider provider = new LocalEmbeddingProvider(dimension);

        assertThat(provider.embed(text)).hasSize(dimension);
    }

    @Provide
    Arbitrary<String> anyText() {
        // Arbitrary unicode text including blanks/empties to exercise the zero-vector edge too.
        return Arbitraries.strings().ofMinLength(0).ofMaxLength(300);
    }
}
