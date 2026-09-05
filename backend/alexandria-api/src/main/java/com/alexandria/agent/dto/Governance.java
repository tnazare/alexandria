package com.alexandria.agent.dto;

/**
 * Governance snapshot for a resolved or fetched skill. Surfaced to agents so they can reason
 * about trust and lifecycle without a second call.
 *
 * @param lifecycleStatus  the skill's lifecycle status (Draft, Published, Team_Approved, ...)
 * @param riskLevel        the assessed risk classification (low, medium, high, restricted); may be null
 * @param visibility       the access scope (private, team, organization, restricted)
 * @param ownerTeam        the owning team's display name; may be null when not projected
 * @param deprecated       true when the skill is in Deprecated status
 * @param deprecationReason optional human-readable reason when deprecated; null otherwise
 */
public record Governance(
        String lifecycleStatus,
        String riskLevel,
        String visibility,
        String ownerTeam,
        boolean deprecated,
        String deprecationReason
) {
}
