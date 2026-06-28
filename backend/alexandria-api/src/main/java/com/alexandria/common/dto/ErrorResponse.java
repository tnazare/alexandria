package com.alexandria.common.dto;

import java.time.Instant;

/**
 * Standard error response body for all API errors.
 */
public record ErrorResponse(
        String error,
        String message,
        Instant timestamp
) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now());
    }
}
