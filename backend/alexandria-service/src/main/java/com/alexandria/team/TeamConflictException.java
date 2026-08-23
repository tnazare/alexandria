package com.alexandria.team;

/**
 * Thrown when a team operation conflicts with existing state,
 * such as duplicate slug or last-owner removal.
 */
public class TeamConflictException extends RuntimeException {

    public TeamConflictException(String message) {
        super(message);
    }
}
