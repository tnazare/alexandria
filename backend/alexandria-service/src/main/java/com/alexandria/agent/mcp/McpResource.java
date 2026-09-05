package com.alexandria.agent.mcp;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal Model Context Protocol (MCP) endpoint exposing the Skill Resolver as two tools,
 * {@code resolve_skill} and {@code fetch_skill} (pull mode).
 *
 * <p>This is a stateless JSON-RPC 2.0 endpoint (the Streamable-HTTP shape MCP clients speak),
 * implemented directly rather than via a milestone Spring AI dependency, so that:
 * <ul>
 *   <li>it runs on the project's Spring Boot 4.0 line without pulling in a milestone stack;</li>
 *   <li>the existing security filter chain applies — both OIDC user tokens and {@code alx_} service
 *       tokens authenticate, and per-identity ACL passes through to {@link com.alexandria.agent.ResolverService}
 *       (unlike Spring AI's MCP transport, which exposes an unauthenticated endpoint by default).</li>
 * </ul>
 *
 * <p>Only two tool definitions are advertised via {@code tools/list}, so MCP clients with
 * tool-search deferral never preload skill catalog content (Requirement 10.7).</p>
 */
@RestController
@RequestMapping("/api/agent/v1/mcp")
@RequiredArgsConstructor
public class McpResource {

    private final McpToolService toolService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode request) {
        AlexandriaUserDetails identity =
                SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                        ? auth.getPrincipal()
                        : null;
        return ResponseEntity.ok(toolService.handle(request, identity));
    }
}
