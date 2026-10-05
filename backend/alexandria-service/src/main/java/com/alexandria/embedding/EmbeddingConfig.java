package com.alexandria.embedding;

import com.alexandria.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link EmbeddingProvider} from {@code alexandria.search.embedding-provider}
 * ({@code local} | {@code http}; default {@code local}). This mirrors the codebase's config-branching
 * convention (a single value drives the choice, as with {@code SearchStrategySelector} and the
 * {@code hookInjectOnPrompt} branch) rather than introducing {@code @ConditionalOnProperty} beans,
 * which the project does not use.
 *
 * <p>The vector dimension for whichever provider is chosen comes from
 * {@link SearchProperties#embeddingDim()}, keeping provider output and the DB column in lockstep
 * (R3.5 is enforced separately at startup against the actual column).</p>
 */
@Configuration
@EnableConfigurationProperties(HttpEmbeddingProperties.class)
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingProvider embeddingProvider(
            SearchProperties searchProperties, HttpEmbeddingProperties httpProperties) {
        int dimension = searchProperties.embeddingDim();
        String provider = searchProperties.embeddingProvider();

        EmbeddingProvider selected = switch (provider) {
            case "http" -> new HttpEmbeddingProvider(dimension, httpProperties);
            case "local" -> new LocalEmbeddingProvider(dimension);
            default -> throw new IllegalStateException(
                    "Unknown alexandria.search.embedding-provider '" + provider
                            + "'. Must be 'local' or 'http' (env ALEXANDRIA_EMBEDDING_PROVIDER).");
        };

        log.info("Embedding provider: {} (modelId={}, dimension={})",
                provider, selected.modelId(), selected.dimension());
        return selected;
    }
}
