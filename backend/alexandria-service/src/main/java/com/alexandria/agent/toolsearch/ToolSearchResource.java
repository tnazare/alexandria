package com.alexandria.agent.toolsearch;

import com.alexandria.agent.ResolverService;
import com.alexandria.agent.dto.ResolveContext;
import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.dto.ResolveResult;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claude-native tool-search backend (fast follow, Requirement 12). An Agent-SDK Claude agent that
 * defers loading its skill catalog can call this endpoint as its custom tool-search backend: it
 * returns {@code tool_reference}-style discovery results (one per resolved candidate) that the
 * agent expands into deferred tool definitions and later invokes via fetch.
 *
 * <p>Enforces the same ACL, governance gates, and org scoping as the resolve/fetch core — discovery
 * only ever references skills the caller may see, and blocked/archived skills are never surfaced
 * (delegated to {@link ResolverService#resolve}). Each reference's name encodes the skill version
 * so the agent can fetch it: {@code alexandria_skill__<skillVersionId>}.</p>
 */
@RestController
@RequestMapping("/api/agent/v1/toolsearch")
@RequiredArgsConstructor
public class ToolSearchResource {

    /** Prefix for a discovered skill's tool name; the suffix is the skillVersionId. */
    public static final String TOOL_NAME_PREFIX = "alexandria_skill__";

    private final ResolverService resolverService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> search(@RequestBody JsonNode request) {
        AlexandriaUserDetails identity =
                SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                        ? auth.getPrincipal()
                        : null;

        String query = request.path("query").asText("");
        Integer limit = request.has("limit") && request.get("limit").isInt()
                ? request.get("limit").asInt() : null;
        String tool = request.path("tool").isMissingNode() ? null : request.path("tool").asText(null);

        ResolveContext context = tool == null ? null : new ResolveContext(null, null, tool, null);
        ResolveResponse response = resolverService.resolve(
                new ResolveRequest(query, context, null, null, limit), identity);

        return ResponseEntity.ok(toToolReferences(response));
    }

    /**
     * Maps resolve candidates to a {@code {"tool_references":[{type,name,description}]}} payload.
     * An empty match set yields an empty array, mirroring the tool-search "no results" contract.
     */
    private JsonNode toToolReferences(ResolveResponse response) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode refs = result.putArray("tool_references");
        for (ResolveResult candidate : response.results()) {
            ObjectNode ref = refs.addObject();
            ref.put("type", "tool_reference");
            ref.put("name", TOOL_NAME_PREFIX + candidate.ref().skillVersionId());
            ref.put("description", describe(candidate));
        }
        return result;
    }

    private String describe(ResolveResult candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append(candidate.name());
        if (candidate.summary() != null && !candidate.summary().isBlank()) {
            sb.append(" — ").append(candidate.summary());
        }
        if (candidate.governance() != null && candidate.governance().lifecycleStatus() != null) {
            sb.append(" [").append(candidate.governance().lifecycleStatus()).append(']');
        }
        return sb.toString();
    }
}
