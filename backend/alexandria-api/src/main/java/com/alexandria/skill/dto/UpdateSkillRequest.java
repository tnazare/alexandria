package com.alexandria.skill.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for updating a skill. All fields are nullable for partial updates.
 */
public record UpdateSkillRequest(
        @Size(min = 3, max = 200) String name,
        @Size(min = 1, max = 2000) String description,
        @Size(min = 1, max = 100000) String canonicalInstructions,
        String applicability,
        String limitations,
        String whenToUse,
        String examples,
        @Size(min = 1, max = 2000) String changelog,
        List<UUID> tagIds,
        List<String> supportedTools,
        String riskLevel
) {
}
