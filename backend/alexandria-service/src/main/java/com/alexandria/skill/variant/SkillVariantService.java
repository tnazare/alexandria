package com.alexandria.skill.variant;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillVariantEntity;

import java.util.UUID;

/**
 * Business logic interface for Skill Variant CRUD and resolution operations.
 */
public interface SkillVariantService {

    /**
     * Creates a new tool-specific variant for a skill version.
     *
     * @param skillId       the skill's UUID
     * @param versionNumber the version number to attach the variant to
     * @param tool          the tool identifier (must be in supported list)
     * @param instructions  the tool-specific instructions (Markdown, 1–100000 characters)
     * @param userDetails   the authenticated user's details
     * @return the created SkillVariantEntity
     */
    SkillVariantEntity create(UUID skillId, int versionNumber, String tool, String instructions,
                              AlexandriaUserDetails userDetails);

    /**
     * Updates an existing variant's instructions.
     *
     * @param skillId       the skill's UUID
     * @param versionNumber the version number
     * @param tool          the tool identifier
     * @param instructions  the updated tool-specific instructions
     * @param userDetails   the authenticated user's details
     * @return the updated SkillVariantEntity
     */
    SkillVariantEntity update(UUID skillId, int versionNumber, String tool, String instructions,
                              AlexandriaUserDetails userDetails);

    /**
     * Deletes an existing variant.
     *
     * @param skillId       the skill's UUID
     * @param versionNumber the version number
     * @param tool          the tool identifier
     * @param userDetails   the authenticated user's details
     */
    void delete(UUID skillId, int versionNumber, String tool, AlexandriaUserDetails userDetails);

    /**
     * Resolves the instructions for a given tool on a skill version.
     *
     * @param skillVersionId the skill version's UUID
     * @param tool           the tool identifier
     * @return the variant's instructions if a variant exists for the tool, or null (caller uses canonical instructions as fallback)
     */
    String resolve(UUID skillVersionId, String tool);
}
