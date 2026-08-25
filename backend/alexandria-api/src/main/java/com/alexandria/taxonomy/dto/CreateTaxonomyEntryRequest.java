package com.alexandria.taxonomy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or updating a taxonomy entry.
 */
public record CreateTaxonomyEntryRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        String value,

        @Size(max = 500)
        String description
) {
}
