package com.alexandria.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alexandria.config.SearchProperties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmbeddingConfig}: provider selection from the {@code embedding-provider}
 * env-driven value, following the codebase's config-branching convention (R3.2).
 */
class EmbeddingConfigTest {

    private final EmbeddingConfig config = new EmbeddingConfig();

    private SearchProperties props(String provider, Integer dim) {
        return new SearchProperties("fts", dim, provider, null);
    }

    @Test
    void defaultProviderIsLocalWithConfiguredDimension() {
        EmbeddingProvider provider = config.embeddingProvider(
                props("local", 256), new HttpEmbeddingProperties(null, null, null));

        assertThat(provider).isInstanceOf(LocalEmbeddingProvider.class);
        assertThat(provider.dimension()).isEqualTo(256);
        assertThat(provider.modelId()).isEqualTo("local-hashing-ngram-v1");
    }

    @Test
    void localProviderHonorsConfiguredDimension() {
        EmbeddingProvider provider = config.embeddingProvider(
                props("local", 512), new HttpEmbeddingProperties(null, null, null));

        assertThat(provider.dimension()).isEqualTo(512);
    }

    @Test
    void httpProviderIsSelectedWhenConfigured() {
        HttpEmbeddingProperties http =
                new HttpEmbeddingProperties("http://embeddings.local/embed", "my-model", "secret");

        EmbeddingProvider provider = config.embeddingProvider(props("http", 256), http);

        assertThat(provider).isInstanceOf(HttpEmbeddingProvider.class);
        assertThat(provider.dimension()).isEqualTo(256);
        assertThat(provider.modelId()).isEqualTo("my-model");
    }

    @Test
    void httpProviderWithoutUrlFailsFast() {
        HttpEmbeddingProperties http = new HttpEmbeddingProperties(null, "my-model", null);

        assertThatThrownBy(() -> config.embeddingProvider(props("http", 256), http))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALEXANDRIA_EMBEDDING_HTTP_URL");
    }
}
