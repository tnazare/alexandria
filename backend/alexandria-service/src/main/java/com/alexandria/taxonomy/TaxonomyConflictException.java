package com.alexandria.taxonomy;

/**
 * Thrown when a taxonomy operation conflicts with existing state,
 * such as duplicate value within a tag type (case-insensitive).
 */
public class TaxonomyConflictException extends RuntimeException {

    public TaxonomyConflictException(String message) {
        super(message);
    }
}
