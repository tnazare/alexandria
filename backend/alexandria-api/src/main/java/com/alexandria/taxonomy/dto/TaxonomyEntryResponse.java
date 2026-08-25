package com.alexandria.taxonomy.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single taxonomy entry.
 */
public record TaxonomyEntryResponse(
        UUID id,
        String tagType,
        String value,
        String description,
        boolean active,
        Instant createdAt
) {
}
