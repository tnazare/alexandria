package com.alexandria.agent.catalog;

import java.util.UUID;

/**
 * One resident Skill Catalog line: identity + display text + governance, no body. {@code skillVersionId}
 * is the skill's current version, which the agent passes straight to {@code fetch_skill} with no extra
 * lookup (R2.4).
 */
public record CatalogEntry(
        UUID skillId,
        UUID skillVersionId,
        String slug,
        String name,
        String description,
        String lifecycleStatus,
        String riskLevel,
        String visibility
) {
}
