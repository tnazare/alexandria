package com.alexandria.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for creating a new skill.
 */
public record CreateSkillRequest(
        @NotBlank @Size(min = 3, max = 200) String name,
        @NotBlank @Size(min = 1, max = 2000) String description,
        @NotNull UUID ownerTeamId,
        @NotBlank @Size(min = 1, max = 100000) String canonicalInstructions
) {
}
