package com.alexandria.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new team.
 */
public record CreateTeamRequest(
        @NotBlank
        @Size(min = 2, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9 \\-_]+$")
        String name,

        String description
) {
}
