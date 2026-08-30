package com.alexandria.skill.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full skill detail response.
 */
public record SkillResponse(
        UUID id,
        String name,
        String slug,
        String description,
        UUID ownerTeamId,
        String ownerTeamName,
        String lifecycleStatus,
        String visibility,
        String riskLevel,
        int currentVersionNumber,
        String canonicalInstructions,
        String applicability,
        String limitations,
        String whenToUse,
        String examples,
        String changelog,
        List<TagResponse> tags,
        List<String> supportedTools,
        List<VariantSummary> variants,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
) {

    /**
     * Summary of a variant included in the skill response.
     */
    public record VariantSummary(
            UUID id,
            String tool,
            String instructions,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
