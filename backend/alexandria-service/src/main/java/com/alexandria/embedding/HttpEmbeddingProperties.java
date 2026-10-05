package com.alexandria.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment-driven configuration for {@link HttpEmbeddingProvider}. Deliberately provider-agnostic:
 * nothing here names a specific vendor, so the same client can talk to a local model sidecar, a
 * self-hosted inference server, or any hosted embedding API that accepts {@code {"input","model"}}
 * and returns an {@code {"embedding":[...]}} vector.
 *
 * <p>Only consulted when {@code alexandria.search.embedding-provider=http}; the {@code local} default
 * ignores it entirely (R3.2).</p>
 *
 * @param url    full endpoint URL to POST embedding requests to (env {@code ALEXANDRIA_EMBEDDING_HTTP_URL})
 * @param model  model name sent in the request body and reported as {@link EmbeddingProvider#modelId()}
 *               (env {@code ALEXANDRIA_EMBEDDING_HTTP_MODEL})
 * @param apiKey optional bearer key; when set, sent as an {@code Authorization: Bearer} header
 *               (env {@code ALEXANDRIA_EMBEDDING_HTTP_API_KEY})
 */
@ConfigurationProperties(prefix = "alexandria.search.embedding-http")
public record HttpEmbeddingProperties(String url, String model, String apiKey) {

    public HttpEmbeddingProperties {
        if (url != null && url.isBlank()) {
            url = null;
        }
        if (model == null || model.isBlank()) {
            model = "default";
        }
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null;
        }
    }
}
