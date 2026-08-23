package com.alexandria.user.dto;

import java.util.UUID;

/**
 * Response DTO for the /api/me endpoint returning the authenticated user's profile.
 */
public record CurrentUserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        UUID organizationId
) {
}
