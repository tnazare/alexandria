package com.alexandria.common.dto;

import java.util.List;

/**
 * Standard paginated response wrapper.
 *
 * @param <T> the type of items in the page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
