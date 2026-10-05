package com.alexandria.embedding;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Default, fully-offline embedding provider. Produces a deterministic dense vector from text using a
 * hashing bag-of-n-grams (the "hashing trick"), with no model download, no network call, and no
 * native dependency — so the backend build and tests stay hermetic.
 *
 * <h2>Design decision (design open-decision #1)</h2>
 * The design floated two options for the local provider: (a) a compose sidecar reusing the
 * experiment's {@code minishlab/potion-base-8M} model for maximal benchmark fidelity, or (b) a
 * pure-JVM hashing / bag-of-n-grams embedding that is simpler and self-contained. This class
 * implements <strong>(b)</strong>. The deciding factor is hermeticity: the backend's own unit tests
 * (determinism, dimension) must run with no external sidecar, model artifact, or network. A pure-JVM
 * embedder is deterministic (R8.1/R8.2), offline, self-contained, and emits vectors of exactly the
 * configured {@link #dimension()}. Benchmark-fidelity against {@code potion-base-8M} is better served
 * by {@link HttpEmbeddingProvider} pointed at a model sidecar when a run needs it — selected purely by
 * the {@code embedding-provider} env var, with no code change. This provider's lower semantic fidelity
 * is an accepted tradeoff for a dependency-free default; it is not meant to compete with a real
 * sentence embedder on recall.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Lowercase the text and tokenize on non-alphanumeric boundaries.</li>
 *   <li>Emit word unigrams and bigrams plus character 3-grams within each token (captures stem/typo
 *       overlap).</li>
 *   <li>Hash each feature to a bucket in {@code [0, dimension)} and add a sign (from a second hash)
 *       so collisions do not systematically inflate magnitude — the standard signed hashing trick.</li>
 *   <li>L2-normalize the accumulated vector. With unit vectors, cosine distance (the HNSW index's
 *       {@code vector_cosine_ops}) reduces to a dot product, and identical text maps to identical
 *       unit vectors.</li>
 * </ol>
 * Hashing is done with a fixed FNV-1a seed so results are stable across JVMs and runs; the
 * {@link #modelId()} version string pins this behavior for reproducibility.
 */
public class LocalEmbeddingProvider implements EmbeddingProvider {

    /**
     * Version tag for this embedding scheme. Bump when the algorithm changes so stale embeddings are
     * distinguishable by {@code model_id} and can be re-generated.
     */
    static final String MODEL_ID = "local-hashing-ngram-v1";

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int dimension;

    /**
     * @param dimension the vector dimension; MUST equal {@code alexandria.search.embedding-dim} and
     *                  the DB column dimension. Must be positive.
     */
    public LocalEmbeddingProvider(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException(
                    "Embedding dimension must be positive, got " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            // A zero vector is a well-defined, deterministic result for empty input. (It cannot be
            // L2-normalized, so it is returned as-is.)
            return vector;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^a-z0-9]+");

        String previous = null;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            // Word unigram.
            addFeature(vector, "w:" + token);
            // Word bigram (ordered) for a little phrase sensitivity.
            if (previous != null) {
                addFeature(vector, "b:" + previous + " " + token);
            }
            // Character 3-grams within the token for sub-word / typo overlap.
            addCharNgrams(vector, token);
            previous = token;
        }

        l2Normalize(vector);
        return vector;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    private void addCharNgrams(float[] vector, String token) {
        int n = 3;
        if (token.length() < n) {
            addFeature(vector, "c:" + token);
            return;
        }
        for (int i = 0; i + n <= token.length(); i++) {
            addFeature(vector, "c:" + token.substring(i, i + n));
        }
    }

    private void addFeature(float[] vector, String feature) {
        long hash = fnv1a(feature);
        int bucket = (int) Math.floorMod(hash, (long) dimension);
        // A second, differently-seeded hash gives a stable +/-1 sign so collisions can cancel rather
        // than only accumulate.
        float sign = (fnv1a("sign:" + feature) & 1L) == 0L ? 1f : -1f;
        vector[bucket] += sign;
    }

    private static long fnv1a(String s) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static void l2Normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            return;
        }
        double norm = Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
