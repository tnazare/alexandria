package com.alexandria.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Request body for adding a member to a team.
 */
public record AddMemberRequest(
        @NotNull UUID userId,
        @NotBlank @Pattern(regexp = "owner|member") String teamRole
) {
}
