package com.alexandria.skill;

/**
 * Thrown when a user lacks the required authority to perform a skill operation.
 * Maps to HTTP 403 in the global exception handler.
 */
public class SkillAccessDeniedException extends RuntimeException {

    public SkillAccessDeniedException(String message) {
        super(message);
    }
}
