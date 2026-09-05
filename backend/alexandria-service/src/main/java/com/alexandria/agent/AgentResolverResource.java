package com.alexandria.agent;

import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST adapter for the agent-facing Skill Resolver. Thin: resolves the current agent identity and
 * delegates to {@link ResolverService}. Governance, ACL, ranking, and formatting all live in the
 * service so every adapter (REST, MCP, hooks) obeys the same rules.
 */
@RestController
@RequiredArgsConstructor
public class AgentResolverResource implements AgentResolverApi {

    private final ResolverService resolverService;

    @Override
    public ResponseEntity<ResolveResponse> resolve(ResolveRequest request) {
        AlexandriaUserDetails identity = currentIdentity();
        return ResponseEntity.ok(resolverService.resolve(request, identity));
    }

    @Override
    public ResponseEntity<?> fetch(UUID skillVersionId, String tool, String format) {
        // Implemented in Task 2 (fetch + Agent Skills / native formats + governance gates).
        throw new UnsupportedOperationException("fetch is implemented in Task 2");
    }

    private AlexandriaUserDetails currentIdentity() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
