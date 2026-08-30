package com.alexandria.skill;

/**
 * Thrown when an export or copy is attempted on a skill whose lifecycle status is
 * {@code Blocked}. Maps to HTTP 409 in the global exception handler.
 */
public class SkillBlockedException extends RuntimeException {

    public SkillBlockedException(String message) {
        super(message);
    }
}
