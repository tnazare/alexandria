package com.alexandria.skill;

import java.util.UUID;

/**
 * Lightweight read projection for a resolve candidate. Deliberately excludes all instruction,
 * example, and limitation bodies — resolve returns identity, governance, and ranking only; full
 * content is served exclusively by fetch.
 */
public interface CandidateProjection {

    UUID getSkillId();

    UUID getSkillVersionId();

    String getName();

    String getDescription();

    String getSlug();

    String getLifecycleStatus();

    String getRiskLevel();

    String getVisibility();

    UUID getOwnerTeamId();

    String getDeprecationReason();

    double getScore();
}
