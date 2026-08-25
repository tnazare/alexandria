package com.alexandria.taxonomy.dto;

import java.util.List;

/**
 * Response DTO for taxonomy entries grouped by tag type.
 */
public record TaxonomyGroupResponse(
        String tagType,
        List<TaxonomyEntryResponse> entries
) {
}
