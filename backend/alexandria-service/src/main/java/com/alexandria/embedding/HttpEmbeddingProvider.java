package com.alexandria.embedding;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Provider-agnostic remote embedding client. Selected by
 * {@code alexandria.search.embedding-provider=http}; configured entirely through environment
 * variables ({@link HttpEmbeddingProperties}), so a deployer points it at any embedding endpoint
 * without touching source — satisfying the steering rule to never hardcode one model vendor (R3.2).
 *
 * <p>The wire contract is intentionally minimal and vendor-neutral: POST a JSON body
 * {@code {"input": <text>, "model": <model>}} and read a float array back from an {@code "embedding"}
 * field (or, if absent, a {@code "data"[0]."embedding"} shape for OpenAI-style servers). Adapting to a
 * different server shape is a config/sidecar concern, not a code change for the common cases.</p>
 *
 * <p>Determinism (R8) is a property of the remote model, not this client; the configured
 * {@link #modelId()} records which model produced a run's vectors so results stay attributable.</p>
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private final int dimension;
    private final HttpEmbeddingProperties properties;
    private final RestClient restClient;

    public HttpEmbeddingProvider(int dimension, HttpEmbeddingProperties properties) {
        this(dimension, properties, RestClient.create());
    }

    HttpEmbeddingProvider(int dimension, HttpEmbeddingProperties properties, RestClient restClient) {
        if (dimension < 1) {
            throw new IllegalArgumentException(
                    "Embedding dimension must be positive, got " + dimension);
        }
        if (properties.url() == null) {
            throw new IllegalStateException(
                    "alexandria.search.embedding-provider=http requires ALEXANDRIA_EMBEDDING_HTTP_URL "
                            + "to be set to the embedding endpoint URL.");
        }
        this.dimension = dimension;
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public float[] embed(String text) {
        String input = text == null ? "" : text;

        Map<?, ?> response = restClient.post()
                .uri(properties.url())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (properties.apiKey() != null) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .body(Map.of("input", input, "model", properties.model()))
                .retrieve()
                .body(Map.class);

        float[] vector = extractEmbedding(response);
        if (vector.length != dimension) {
            throw new IllegalStateException(
                    "Embedding endpoint returned a vector of dimension " + vector.length
                            + " but ALEXANDRIA_EMBEDDING_DIM=" + dimension
                            + ". Align the configured dimension with the model.");
        }
        return vector;
    }

    private float[] extractEmbedding(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("Embedding endpoint returned an empty response body.");
        }
        Object embedding = response.get("embedding");
        if (embedding == null) {
            // OpenAI-style envelope: {"data":[{"embedding":[...]}]}
            Object data = response.get("data");
            if (data instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first) {
                embedding = first.get("embedding");
            }
        }
        if (!(embedding instanceof List<?> values)) {
            throw new IllegalStateException(
                    "Embedding endpoint response did not contain an 'embedding' array.");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Number number)) {
                throw new IllegalStateException(
                        "Embedding array contained a non-numeric element at index " + i + ".");
            }
            vector[i] = number.floatValue();
        }
        return vector;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelId() {
        return properties.model();
    }
}
