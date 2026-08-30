package com.alexandria.skill.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response when skill publishing fails validation — lists the missing fields.
 */
public record PublishValidationResponse(
        String error,
        String message,
        List<String> missingFields,
        Instant timestamp
) {
}
