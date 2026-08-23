package com.alexandria.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for user listing endpoints.
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        boolean enabled,
        UUID organizationId,
        Instant createdAt
) {
}
