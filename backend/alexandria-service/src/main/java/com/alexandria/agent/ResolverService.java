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
}
