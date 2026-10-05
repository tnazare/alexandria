package com.alexandria.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the agent-facing Skill Resolver's retrieval mode. All values are
 * environment-variable driven so the retrieval strategy (FTS / semantic / hybrid) can be selected
 * without source changes or redeploying new images.
 *
 * <p>Follows the existing {@link ResolverProperties} record pattern and is registered in
 * {@code SecurityConfig}'s {@code @EnableConfigurationProperties}. Defaults keep existing
 * deployments on full-text search with zero behavior change.</p>
 *
 * @param mode              retrieval strategy: {@code fts} | {@code semantic} | {@code hybrid}
 *                          (env {@code ALEXANDRIA_SEARCH_MODE}; default {@code fts}). An unrecognized
 *                          value fails fast at startup rather than silently falling back, so a
 *                          misconfigured run is never mislabeled.
 * @param embeddingDim      embedding vector dimension; MUST match the DB column dimension
 *                          (env {@code ALEXANDRIA_EMBEDDING_DIM}; default 256, matching the local
 *                          provider)
 * @param embeddingProvider embedding source: {@code local} | {@code http}
 *                          (env {@code ALEXANDRIA_EMBEDDING_PROVIDER}; default {@code local})
 * @param hybrid            hybrid fusion tuning (defaults: {@code rrf} fusion, k=60, 0.5/0.5 weights)
 */
@ConfigurationProperties(prefix = "alexandria.search")
public record SearchProperties(
        String mode,
        Integer embeddingDim,
        String embeddingProvider,
        HybridProps hybrid
) {

    /** Valid retrieval modes. An unknown mode is rejected at startup (R1.3). */
    public static final Set<String> VALID_MODES = Set.of("fts", "semantic", "hybrid");

    /**
     * Hybrid-mode fusion parameters.
     *
     * @param fusion         fusion method: {@code rrf} (rank-based, default) | {@code weighted}
     *                       (normalized-score blend)
     * @param ftsWeight      weight for FTS scores when {@code fusion=weighted} (default 0.5)
     * @param semanticWeight weight for semantic scores when {@code fusion=weighted} (default 0.5)
     * @param rrfK           Reciprocal Rank Fusion constant k (default 60)
     */
    public record HybridProps(String fusion, Double ftsWeight, Double semanticWeight, Integer rrfK) {

        public HybridProps {
            if (fusion == null || fusion.isBlank()) {
                fusion = "rrf";
            }
            if (ftsWeight == null) {
                ftsWeight = 0.5;
            }
            if (semanticWeight == null) {
                semanticWeight = 0.5;
            }
            if (rrfK == null || rrfK < 1) {
                rrfK = 60;
            }
        }
    }

    public SearchProperties {
        if (mode == null || mode.isBlank()) {
            mode = "fts";
        }
        mode = mode.trim().toLowerCase();
        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "Invalid alexandria.search.mode '" + mode + "'. Must be one of " + VALID_MODES
                            + " (env ALEXANDRIA_SEARCH_MODE).");
        }
        if (embeddingDim == null || embeddingDim < 1) {
            embeddingDim = 256;
        }
        if (embeddingProvider == null || embeddingProvider.isBlank()) {
            embeddingProvider = "local";
        }
        if (hybrid == null) {
            hybrid = new HybridProps(null, null, null, null);
        }
    }
}
