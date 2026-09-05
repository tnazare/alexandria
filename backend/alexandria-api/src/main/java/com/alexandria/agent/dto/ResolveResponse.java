package com.alexandria.agent.dto;

import java.util.List;

/**
 * The forward-compatible envelope returned by resolve. Wraps ranked candidates with a schema
 * version and a capabilities advertisement so orchestration can be added later without a
 * breaking wire change.
 *
 * @param queryId       an opaque id for this resolve invocation (useful for correlation/telemetry)
 * @param schemaVersion the response schema version (e.g. "1.0")
 * @param capabilities  what this instance supports (resolve/fetch true, invoke/compose false)
 * @param results       the ranked candidates (may be empty when nothing matches)
 */
public record ResolveResponse(
        String queryId,
        String schemaVersion,
        Capabilities capabilities,
        List<ResolveResult> results
) {
}
