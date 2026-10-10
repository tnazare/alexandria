package com.alexandria.skill;

import java.util.UUID;

/**
 * Lightweight read projection for one resident Skill Catalog line. Carries only what a catalog line
 * needs — identity, display text, and governance — and deliberately excludes all instruction,
 * example, and limitation bodies (those are served exclusively by {@code fetch_skill}).
 *
 * <p>Unlike {@link CandidateProjection}, there is no ranking {@code score}: the catalog is an
 * unranked, ACL-scoped listing ordered deterministically by name (R1.3, R1.4). {@code skillVersionId}
 * is the skill's {@code current_version_id}, which the agent passes straight to {@code fetch_skill}
 * with no extra lookup (R2.4).</p>
 */
public interface CatalogEntryProjection {

    UUID getSkillId();

    UUID getSkillVersionId();

    String getSlug();

    String getName();

    String getDescription();

    String getLifecycleStatus();

    String getRiskLevel();

    String getVisibility();
}
