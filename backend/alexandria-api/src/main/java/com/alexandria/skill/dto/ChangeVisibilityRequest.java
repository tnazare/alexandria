package com.alexandria.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for changing a skill's visibility.
 */
public record ChangeVisibilityRequest(
        @NotBlank @Pattern(regexp = "private|team|organization|restricted") String visibility
) {
}
