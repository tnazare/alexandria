package com.alexandria.skill.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill list item response (summary view).
 */
public record SkillSummaryResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String ownerTeamName,
        String lifecycleStatus,
        String visibility,
        String riskLevel,
        int currentVersionNumber,
        List<TagResponse> tags,
        Instant updatedAt
) {
}
