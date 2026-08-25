package com.alexandria.skill;

/**
 * Thrown when a skill or related resource cannot be found.
 * Maps to HTTP 404 in the global exception handler.
 */
public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String message) {
        super(message);
    }
}
