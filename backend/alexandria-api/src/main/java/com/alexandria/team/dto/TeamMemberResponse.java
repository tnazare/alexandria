package com.alexandria.team.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a team member.
 */
public record TeamMemberResponse(
        UUID userId,
        String email,
        String displayName,
        String teamRole,
        Instant joinedAt
) {
}
