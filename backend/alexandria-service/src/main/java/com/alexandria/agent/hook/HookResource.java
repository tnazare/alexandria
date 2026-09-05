package com.alexandria.agent.hook;

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

/**
 * Push-mode hook endpoint (Requirement 11). Agent harnesses point their prompt/tool lifecycle hooks
 * at this endpoint; it deterministically resolves and returns a governed skill to inject, without
 * relying on the model choosing to call a tool.
 *
 * <p>Guarded by the existing security chain (OIDC or {@code alx_} service token), so injection is
 * scoped to the caller's ACL. Always returns HTTP 200 with a JSON decision object; an empty object
 * means "proceed unmodified" (fail-open).</p>
 */
@RestController
@RequestMapping("/api/agent/v1/hooks")
@RequiredArgsConstructor
public class HookResource {

    private final HookPayloadParser parser;
    private final HookService hookService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode payload) {
        AlexandriaUserDetails identity =
                SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                        ? auth.getPrincipal()
                        : null;
        HookInvocation invocation = parser.parse(payload);
        return ResponseEntity.ok(hookService.handle(invocation, identity));
    }
}
