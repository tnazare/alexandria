package com.alexandria.team.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for team detail including members.
 */
public record TeamDetailResponse(
        UUID id,
        String name,
        String slug,
        String description,
        List<TeamMemberResponse> members,
        Instant createdAt
) {
}
