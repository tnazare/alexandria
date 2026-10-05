package com.alexandria.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SearchProperties} — the retrieval-mode feature flag.
 *
 * <p>Covers R1.1 (unset defaults to fts), R1.2 (each valid mode accepted), and R1.3 (unknown mode
 * fails fast rather than silently falling back).</p>
 */
class SearchPropertiesTest {

    @Test
    void unsetModeDefaultsToFts() {
        // R1.1: null/blank mode and null tuning fields all fall back to safe defaults.
        SearchProperties props = new SearchProperties(null, null, null, null);

        assertThat(props.mode()).isEqualTo("fts");
        assertThat(props.embeddingDim()).isEqualTo(256);
        assertThat(props.embeddingProvider()).isEqualTo("local");
        assertThat(props.hybrid()).isNotNull();
        assertThat(props.hybrid().fusion()).isEqualTo("rrf");
        assertThat(props.hybrid().rrfK()).isEqualTo(60);
        assertThat(props.hybrid().ftsWeight()).isEqualTo(0.5);
        assertThat(props.hybrid().semanticWeight()).isEqualTo(0.5);
    }

    @Test
    void blankModeDefaultsToFts() {
        assertThat(new SearchProperties("   ", null, null, null).mode()).isEqualTo("fts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fts", "semantic", "hybrid"})
    void eachValidModeIsAccepted(String mode) {
        // R1.2: fts, semantic, hybrid are all honored verbatim.
        assertThat(new SearchProperties(mode, null, null, null).mode()).isEqualTo(mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FTS", "Semantic", "  HYBRID "})
    void validModeIsNormalizedCaseAndWhitespaceInsensitively(String mode) {
        assertThat(new SearchProperties(mode, null, null, null).mode())
                .isEqualTo(mode.trim().toLowerCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"keyword", "vector", "ftss", "sem", "none", "0"})
    void unknownModeFailsFast(String mode) {
        // R1.3: an unrecognized mode throws (Spring context fails to start) — never a silent fallback.
        assertThatThrownBy(() -> new SearchProperties(mode, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alexandria.search.mode")
                .hasMessageContaining(mode);
    }

    @Test
    void customEmbeddingSettingsArePreserved() {
        SearchProperties props = new SearchProperties("semantic", 512, "http", null);

        assertThat(props.embeddingDim()).isEqualTo(512);
        assertThat(props.embeddingProvider()).isEqualTo("http");
    }

    @Test
    void nonPositiveEmbeddingDimFallsBackToDefault() {
        assertThat(new SearchProperties("fts", 0, null, null).embeddingDim()).isEqualTo(256);
        assertThat(new SearchProperties("fts", -4, null, null).embeddingDim()).isEqualTo(256);
    }

    @Test
    void customHybridTuningIsPreserved() {
        SearchProperties.HybridProps hybrid =
                new SearchProperties.HybridProps("weighted", 0.7, 0.3, 40);
        SearchProperties props = new SearchProperties("hybrid", null, null, hybrid);

        assertThat(props.hybrid().fusion()).isEqualTo("weighted");
        assertThat(props.hybrid().ftsWeight()).isEqualTo(0.7);
        assertThat(props.hybrid().semanticWeight()).isEqualTo(0.3);
        assertThat(props.hybrid().rrfK()).isEqualTo(40);
    }
}
