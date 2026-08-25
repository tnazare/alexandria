package com.alexandria.skill;

import java.util.List;

/**
 * Thrown when a skill cannot be published because required metadata is missing.
 * Maps to HTTP 422 (Unprocessable Entity) in the global exception handler.
 */
public class PublishValidationException extends RuntimeException {

    private final List<String> missingFields;

    public PublishValidationException(List<String> missingFields) {
        super("Skill cannot be published. Missing required metadata: " + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
