package com.alexandria.embedding;

/**
 * Produces a fixed-dimension dense vector for a piece of text, so skills can be ranked by meaning
 * rather than keyword overlap (R3.1).
 *
 * <p>This is the vendor-abstraction seam demanded by the steering ("embedding provider abstraction —
 * never hardcode one model vendor"): the resolver depends only on this interface, and the concrete
 * implementation is selected at startup from {@code alexandria.search.embedding-provider}
 * ({@code local} | {@code http}; default {@code local}). A self-hosted deployment runs fully offline
 * on {@link LocalEmbeddingProvider}; a deployer who wants a hosted model points
 * {@link HttpEmbeddingProvider} at any provider-agnostic REST endpoint via environment variables.</p>
 *
 * <p>Every provider MUST return vectors of exactly {@link #dimension()} length, and that dimension
 * MUST match the {@code skill_embeddings.embedding} column — a mismatch is caught at startup
 * (R3.5).</p>
 */
public interface EmbeddingProvider {

    /**
     * Embeds {@code text} into a dense vector of exactly {@link #dimension()} elements.
     *
     * <p>Implementations SHALL be deterministic for a given model/version: the same input text MUST
     * always produce the same vector, so a benchmark corpus re-embedded with the same provider
     * yields stable rankings (R8.1, R8.2). {@code null} or blank text yields a zero vector of the
     * configured dimension.</p>
     *
     * @param text the source text to embed (may be {@code null} or blank)
     * @return a float array of length {@link #dimension()}
     */
    float[] embed(String text);

    /**
     * The fixed dimensionality of every vector this provider emits. MUST equal the configured
     * {@code alexandria.search.embedding-dim} and the DB column dimension.
     */
    int dimension();

    /**
     * A stable identifier for the model/version behind this provider (e.g.
     * {@code local-hashing-ngram-v1} or a remote model name). Persisted on each embedding row as
     * provenance so a run's semantic results are reproducible and attributable (R8.1).
     */
    String modelId();
}
