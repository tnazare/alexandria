package com.alexandria.skill.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill version detail response.
 */
public record SkillVersionResponse(
        UUID id,
        int versionNumber,
        String canonicalInstructions,
        String applicability,
        String limitations,
        String whenToUse,
        String examples,
        String changelog,
        List<SkillResponse.VariantSummary> variants,
        Instant createdAt,
        Instant publishedAt
) {
}
