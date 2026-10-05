package com.alexandria.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpEmbeddingProvider} against a tiny in-process HTTP server, so the
 * provider-agnostic wire contract and response parsing are exercised for real with no network or
 * vendor dependency (R3.2).
 */
class HttpEmbeddingProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private volatile String responseBody = "{\"embedding\":[0.1,0.2,0.3]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesFlatEmbeddingArrayAndMatchesDimension() {
        responseBody = "{\"embedding\":[0.1,0.2,0.3]}";
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                3, new HttpEmbeddingProperties(baseUrl, "test-model", null));

        float[] vector = provider.embed("hello");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(lastRequestBody.get()).contains("\"input\":\"hello\"").contains("\"model\":\"test-model\"");
    }

    @Test
    void sendsBearerAuthWhenApiKeyConfigured() {
        responseBody = "{\"embedding\":[1.0,2.0]}";
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                2, new HttpEmbeddingProperties(baseUrl, "m", "s3cr3t"));

        provider.embed("x");

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer s3cr3t");
    }

    @Test
    void parsesOpenAiStyleEnvelope() {
        responseBody = "{\"data\":[{\"embedding\":[0.5,0.6]}]}";
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                2, new HttpEmbeddingProperties(baseUrl, "m", null));

        assertThat(provider.embed("x")).containsExactly(0.5f, 0.6f);
    }

    @Test
    void dimensionMismatchFromServerFailsFast() {
        responseBody = "{\"embedding\":[0.1,0.2,0.3]}";
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                2, new HttpEmbeddingProperties(baseUrl, "m", null));

        assertThatThrownBy(() -> provider.embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void missingEmbeddingFieldFailsClearly() {
        responseBody = "{\"nope\":true}";
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                2, new HttpEmbeddingProperties(baseUrl, "m", null));

        assertThatThrownBy(() -> provider.embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding");
    }

    @Test
    void missingUrlFailsFastAtConstruction() {
        assertThatThrownBy(() -> new HttpEmbeddingProvider(
                2, new HttpEmbeddingProperties(null, "m", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALEXANDRIA_EMBEDDING_HTTP_URL");
    }
}
