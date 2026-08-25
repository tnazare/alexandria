package com.alexandria.skill;

/**
 * Thrown when a skill operation conflicts with the current lifecycle state.
 * Maps to HTTP 409 in the global exception handler.
 */
public class SkillConflictException extends RuntimeException {

    public SkillConflictException(String message) {
        super(message);
    }
}
