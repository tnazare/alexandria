package com.alexandria.agent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to resolve the most relevant governed skills for a task.
 *
 * @param query         required free-text description of the task; must express an intent
 * @param context       optional coding context (languages, frameworks, tool, active file)
 * @param filters       optional lifecycle/risk/tool filters (AND predicates, never widen access)
 * @param referenceMode optional reference mode (pinned, floating_latest_approved,
 *                      floating_latest_published); defaults to floating_latest_approved
 * @param limit         optional max candidates; defaults to 5, clamped to 25
 */
public record ResolveRequest(
        @NotBlank String query,
        ResolveContext context,
        ResolveFilters filters,
        String referenceMode,
        Integer limit
) {
}
