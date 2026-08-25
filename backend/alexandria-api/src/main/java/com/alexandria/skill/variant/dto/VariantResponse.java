package com.alexandria.skill.variant.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a skill variant.
 */
public record VariantResponse(
        UUID id,
        String tool,
        String instructions,
        Instant createdAt,
        Instant updatedAt
) {
}
