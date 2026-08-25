package com.alexandria.skill.variant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or updating a skill variant.
 */
public record CreateVariantRequest(
        @NotBlank
        @Pattern(regexp = "generic|github_copilot|codex|cursor|aws_kiro|sourcegraph_cody|claude_code|jetbrains_ai|internal_agent")
        String tool,

        @NotBlank
        @Size(min = 1, max = 100000)
        String instructions
) {
}
