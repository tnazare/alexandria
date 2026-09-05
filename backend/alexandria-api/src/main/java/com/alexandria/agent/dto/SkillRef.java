package com.alexandria.agent.dto;

import java.util.UUID;

/**
 * Embedded identity carried by every resolve candidate and every fetch response, in every
 * output format, so that a copied or committed skill artifact can always be traced back to
 * its Alexandria origin and later revalidated.
 *
 * @param skillId        the stable skill identity
 * @param skillVersionId the resolved version identity
 * @param sourceInstance the base URL of the Alexandria instance that produced this artifact
 *                       (derived from configuration, never hardcoded)
 */
public record SkillRef(
        UUID skillId,
        UUID skillVersionId,
        String sourceInstance
) {
}
