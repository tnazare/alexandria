package com.alexandria.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alexandria.agent.ResolverService;
import com.alexandria.agent.catalog.CatalogEntry;
import com.alexandria.agent.catalog.CatalogRenderer;
import com.alexandria.agent.catalog.CatalogResult;
import com.alexandria.agent.catalog.CatalogService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.CatalogProperties;
import com.alexandria.user.entity.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unit tests for the catalog-resource surface of {@link McpToolService}: the {@code resources}
 * capability and {@code resources/list} / {@code resources/read} are gated by
 * {@code alexandria.catalog.enabled}, while {@code resolve_skill} / {@code fetch_skill} are
 * unaffected either way.
 *
 * <p>Validates: skill-catalog R3.1, R3.2, R3.3, R3.4, R4.2, R4.3, R8.1, R8.2</p>
 */
class McpToolServiceCatalogTest {

    private static final int METHOD_NOT_FOUND = -32601;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResolverService resolverService = mock(ResolverService.class);
    private final CatalogService catalogService = mock(CatalogService.class);

    private AlexandriaUserDetails identity() {
        return new AlexandriaUserDetails(UUID.randomUUID(), UUID.randomUUID(), Role.Skill_Consumer,
                "u@test.com", true);
    }

    private McpToolService service(boolean enabled) {
        CatalogProperties props = new CatalogProperties(enabled, 500, false, "all", false);
        CatalogRenderer renderer = new CatalogRenderer(props);
        return new McpToolService(resolverService, catalogService, renderer, props);
    }

    private JsonNode request(String method) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        return req;
    }

    private JsonNode readRequest(String uri) {
        ObjectNode req = (ObjectNode) request("resources/read");
        req.putObject("params").put("uri", uri);
        return req;
    }

    // ---------------- flag OFF (default) — unchanged behavior (R4.2, R8.1) ----------------

    @Test
    void initializeAdvertisesToolsOnlyWhenDisabled() {
        JsonNode resp = service(false).handle(request("initialize"), identity());
        JsonNode caps = resp.path("result").path("capabilities");
        assertThat(caps.has("tools")).isTrue();
        assertThat(caps.has("resources")).isFalse();
    }

    @Test
    void resourcesListIsMethodNotFoundWhenDisabled() {
        JsonNode resp = service(false).handle(request("resources/list"), identity());
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(METHOD_NOT_FOUND);
    }

    @Test
    void resourcesReadIsMethodNotFoundWhenDisabled() {
        JsonNode resp = service(false).handle(readRequest("alexandria://catalog"), identity());
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(METHOD_NOT_FOUND);
    }

    // ---------------- flag ON — resources active (R3.1-R3.4, R4.3) ----------------

    @Test
    void initializeAdvertisesResourcesWhenEnabled() {
        JsonNode resp = service(true).handle(request("initialize"), identity());
        JsonNode caps = resp.path("result").path("capabilities");
        assertThat(caps.has("tools")).isTrue();
        assertThat(caps.has("resources")).isTrue();
    }

    @Test
    void resourcesListAdvertisesTheCatalogResourceWhenEnabled() {
        JsonNode resp = service(true).handle(request("resources/list"), identity());
        JsonNode resources = resp.path("result").path("resources");
        assertThat(resources.isArray()).isTrue();
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).path("uri").asText()).isEqualTo("alexandria://catalog");
        assertThat(resources.get(0).path("mimeType").asText()).isEqualTo("text/markdown");
    }

    @Test
    void resourcesReadReturnsRenderedCatalogForAuthenticatedIdentity() {
        UUID vid = UUID.randomUUID();
        when(catalogService.catalog(any(), any(), any())).thenReturn(new CatalogResult(
                List.of(new CatalogEntry(UUID.randomUUID(), vid, "pdf-tools", "PDF", "work with pdfs",
                        "Published", "low", "organization")),
                false, 1));

        JsonNode resp = service(true).handle(readRequest("alexandria://catalog"), identity());

        assertThat(resp.has("error")).as("unexpected error: %s", resp.path("error")).isFalse();
        JsonNode contents = resp.path("result").path("contents");
        assertThat(contents.isArray()).isTrue();
        assertThat(contents.get(0).path("uri").asText()).isEqualTo("alexandria://catalog");
        assertThat(contents.get(0).path("mimeType").asText()).isEqualTo("text/markdown");
        String text = contents.get(0).path("text").asText();
        assertThat(text).contains("# Skill Catalog");
        assertThat(text).contains("pdf-tools: PDF \u2014 work with pdfs");
    }

    @Test
    void resourcesReadRejectsUnknownUri() {
        JsonNode resp = service(true).handle(readRequest("alexandria://nope"), identity());
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void resourcesReadRequiresAuthenticatedIdentity() {
        JsonNode resp = service(true).handle(readRequest("alexandria://catalog"), null);
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    // ---------------- tools unaffected either way (R8.2) ----------------

    @Test
    void toolsListAdvertisesTheTwoResolverToolsRegardlessOfFlag() {
        for (boolean enabled : new boolean[] {false, true}) {
            JsonNode resp = service(enabled).handle(request("tools/list"), identity());
            JsonNode tools = resp.path("result").path("tools");
            assertThat(tools).hasSize(2);
            assertThat(tools.get(0).path("name").asText()).isEqualTo("resolve_skill");
            assertThat(tools.get(1).path("name").asText()).isEqualTo("fetch_skill");
        }
    }
}
