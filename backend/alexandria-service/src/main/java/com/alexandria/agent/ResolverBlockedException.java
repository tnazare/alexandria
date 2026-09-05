package com.alexandria.agent;

/**
 * Thrown when an agent attempts to fetch a skill whose lifecycle status is {@code Blocked}.
 *
 * <p>Unlike the human-facing export path (which maps blocked to 409 Conflict), the agent resolver
 * denies a fetch of a <em>visible</em> blocked skill with 403 Forbidden (skill-resolver
 * Requirement 4.1). A skill the identity cannot see is instead reported as 404 by the visibility
 * check, so this exception is only reached for skills the caller is otherwise allowed to see.</p>
 */
public class ResolverBlockedException extends RuntimeException {

    public ResolverBlockedException(String message) {
        super(message);
    }
}
