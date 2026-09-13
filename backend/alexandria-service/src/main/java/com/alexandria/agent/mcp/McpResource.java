package com.alexandria.agent.mcp;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal Model Context Protocol (MCP) endpoint exposing the Skill Resolver as two tools,
 * {@code resolve_skill} and {@code fetch_skill} (pull mode).
 *
 * <p>This is a stateless JSON-RPC 2.0 endpoint conforming to the MCP Streamable HTTP transport for
 * the request/response (non-streaming) case. It is implemented directly rather than via a milestone
 * Spring AI dependency, so that:
 * <ul>
 *   <li>it runs on the project's Spring Boot 4.0 line without pulling in a milestone stack;</li>
 *   <li>the existing security filter chain applies — both OIDC user tokens and {@code alx_} service
 *       tokens authenticate, and per-identity ACL passes through to {@link com.alexandria.agent.ResolverService}
 *       (unlike Spring AI's MCP transport, which exposes an unauthenticated endpoint by default).</li>
 * </ul>
 *
 * <p><b>Transport conformance.</b> The POST handler declares no {@code consumes}/{@code produces} so
 * a client's dual {@code Accept: application/json, text/event-stream} header and a
 * {@code charset} content-type parameter are accepted without {@code 406}/{@code 415}. A JSON-RPC
 * message with no {@code id} is a notification (e.g. {@code notifications/initialized}) and is
 * acknowledged with {@code 202 Accepted} and no body; a message with an {@code id} is a request and
 * is answered with {@code 200 OK} and an {@code application/json} JSON-RPC result. {@code GET} and
 * {@code DELETE} return {@code 405} (no server-initiated SSE stream, no stateful sessions). The
 * endpoint is stateless and does not require an {@code Mcp-Session-Id}.</p>
 *
 * <p>Only two tool definitions are advertised via {@code tools/list}, so MCP clients with
 * tool-search deferral never preload skill catalog content.</p>
 */
@RestController
@RequestMapping("/api/agent/v1/mcp")
@RequiredArgsConstructor
public class McpResource {

    private final McpToolService toolService;

    /**
     * Handles a single JSON-RPC message. Requests (with an {@code id}) return a JSON result;
     * notifications (no {@code id}) return {@code 202} with an empty body.
     */
    @PostMapping
    public ResponseEntity<?> handle(@RequestBody JsonNode request) {
        if (isNotification(request)) {
            // Notifications (and any id-less message, e.g. notifications/initialized) are
            // acknowledged with 202 and no body per the Streamable HTTP transport.
            return ResponseEntity.accepted().build();
        }

        AlexandriaUserDetails identity =
                SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                        ? auth.getPrincipal()
                        : null;

        JsonNode response = toolService.handle(request, identity);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /** No server-initiated SSE stream is offered at this endpoint. */
    @GetMapping
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /** The endpoint is stateless; there is no session to tear down. */
    @DeleteMapping
    public ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /** A JSON-RPC message with no {@code id} (or a null {@code id}) is a notification. */
    private static boolean isNotification(JsonNode request) {
        return request == null || !request.has("id") || request.get("id").isNull();
    }
}
