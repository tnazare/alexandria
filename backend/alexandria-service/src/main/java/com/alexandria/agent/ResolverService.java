package com.alexandria.agent;

import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.auth.AlexandriaUserDetails;

/**
 * Core agent-facing retrieval service. Owns resolve (and, from Task 2, fetch), enforcing the same
 * ACL, visibility, and lifecycle governance as the human-facing catalog. Adapters (REST, MCP,
 * hooks, tool-search) are thin shells over this service.
 */
public interface ResolverService {

    /**
     * Resolves the most relevant governed skill candidates for a task.
     *
     * @param request  the resolve request (query required; context/filters/mode/limit optional)
     * @param identity the authenticated agent identity, used for ACL and org scoping
     * @return a forward-compatible envelope of ranked candidates (no instruction bodies)
     * @throws IllegalArgumentException if the query is blank after trimming
     */
    ResolveResponse resolve(ResolveRequest request, AlexandriaUserDetails identity);

    /**
     * Fetches the full instructions for one chosen skill version, applying governance gates.
     *
     * <p>Gate ladder: not visible to the identity → {@link com.alexandria.skill.SkillNotFoundException}
     * (404, anti-enumeration); skill Blocked → {@link ResolverBlockedException} (403); Deprecated →
     * returned with a warning; otherwise returned normally. A {@code view} usage event is recorded
     * on success.</p>
     *
     * @param skillVersionId the version to fetch
     * @param tool           optional preferred variant tool (falls back to canonical)
     * @param format         output format ({@code agent_skill} default, or {@code alexandria_native})
     * @param source         the adapter recording the usage event (e.g. {@code rest}, {@code mcp})
     * @param identity       the authenticated agent identity
     * @return the rendered result (media type + body)
     */
    com.alexandria.agent.render.FetchResult fetch(
            java.util.UUID skillVersionId, String tool, String format, String source,
            AlexandriaUserDetails identity);
}
