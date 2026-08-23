package com.alexandria.team.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for team list items.
 */
public record TeamResponse(
        UUID id,
        String name,
        String slug,
        String description,
        int memberCount,
        Instant createdAt
) {
}
