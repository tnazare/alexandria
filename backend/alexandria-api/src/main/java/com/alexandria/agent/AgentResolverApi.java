package com.alexandria.agent;

import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Agent-facing REST API contract for the Skill Resolver.
 *
 * <p>Exposes two operations: {@code resolve} (cheap, ranked candidates, no instruction bodies) and
 * {@code fetch} (governed full instructions for one chosen skill version, in a requested format).
 * Both enforce the same ACL, visibility, and lifecycle governance as the human-facing catalog,
 * scoped to the authenticated agent identity (user OIDC token or service token).</p>
 */
@RequestMapping("/api/agent/v1/skills")
public interface AgentResolverApi {

    /**
     * Resolves the most relevant governed skill candidates for a task. Returns ranked candidates
     * with identity, governance, and match reasons but no full instructions.
     */
    @PostMapping("/resolve")
    ResponseEntity<ResolveResponse> resolve(@Valid @RequestBody ResolveRequest request);

    /**
     * Fetches the full instructions for one chosen skill version, subject to governance gates
     * (Blocked denied, Deprecated warned, not-visible treated as not found).
     *
     * @param skillVersionId the version to fetch
     * @param tool           optional preferred variant tool; falls back to canonical when absent
     * @param format         output format: {@code agent_skill} (default, SKILL.md) or
     *                       {@code alexandria_native} (structured JSON)
     * @return the rendered skill; {@code text/markdown} for agent_skill, JSON for native
     */
    @GetMapping("/{skillVersionId}/instructions")
    ResponseEntity<?> fetch(
            @PathVariable UUID skillVersionId,
            @RequestParam(required = false) String tool,
            @RequestParam(defaultValue = "agent_skill") String format
    );
}
