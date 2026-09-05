package com.alexandria.agent.dto;

/**
 * How a resolved skill is pinned over time and whether it may be committed. Populated by the
 * resolver policy from the caller's requested reference mode and the skill's risk level.
 *
 * @param mode                 pinned, floating_latest_approved, or floating_latest_published
 * @param resolvedVersion      the concrete version number resolved for this request, as a string
 * @param commitAllowed        false when admin policy forbids committing this artifact to a repo
 * @param requiresRevalidation true when the artifact should be revalidated against the instance
 *                             before reuse (e.g. high/restricted risk under pin-and-revalidate)
 * @param revalidateHint       a stable reference usable by a future governance-revalidation
 *                             endpoint; the endpoint itself is out of scope for this spec
 */
public record Resolution(
        String mode,
        String resolvedVersion,
        boolean commitAllowed,
        boolean requiresRevalidation,
        String revalidateHint
) {
}
