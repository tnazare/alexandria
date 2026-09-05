package com.alexandria.agent.dto;

import java.util.List;

/**
 * Optional filters narrowing a resolve request. Applied as additional AND predicates on top of
 * the mandatory visibility and lifecycle enforcement — filters never widen access.
 *
 * @param lifecycleStates optional lifecycle states to include (e.g. Team_Approved, Company_Approved);
 *                        requesting Archived here is the only way Archived skills appear
 * @param riskLevels      optional risk levels to include (e.g. low, medium)
 * @param tools           optional tool identifiers the skill must support
 */
public record ResolveFilters(
        List<String> lifecycleStates,
        List<String> riskLevels,
        List<String> tools
) {
}
