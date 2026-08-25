package com.alexandria.skill;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Business logic interface for Skill lifecycle operations.
 */
public interface SkillService {

    /**
     * Creates a new Skill in Draft status with an initial version.
     *
     * @param name                  the skill name (3–200 characters)
     * @param description           the skill description (1–2000 characters)
     * @param ownerTeamId           the owning team's ID (must exist in user's organization)
     * @param canonicalInstructions the initial canonical instructions (Markdown, 1–100000 characters)
     * @param userDetails           the authenticated user's details
     * @return the created SkillEntity with currentVersionId set
     * @throws SkillNotFoundException      if the owner team does not exist in the user's organization
     * @throws SkillAccessDeniedException  if the user is not a member of the owner team and lacks admin role
     */
    SkillEntity create(String name, String description, UUID ownerTeamId,
                       String canonicalInstructions, AlexandriaUserDetails userDetails);

    /**
     * Retrieves a skill by ID with visibility enforcement.
     *
     * @param skillId     the skill's UUID
     * @param userDetails the authenticated user's details
     * @return the SkillEntity
     * @throws SkillNotFoundException if the skill does not exist or the user lacks visibility
     */
    SkillEntity getById(UUID skillId, AlexandriaUserDetails userDetails);

    /**
     * Lists skills visible to the user with optional filters.
     *
     * @param status      optional lifecycle status filter
     * @param teamId      optional owner team filter
     * @param searchQuery optional full-text search query
     * @param userDetails the authenticated user's details
     * @param pageable    pagination parameters
     * @return a page of visible SkillEntity results
     */
    Page<SkillEntity> list(String status, UUID teamId, String searchQuery,
                           AlexandriaUserDetails userDetails, Pageable pageable);

    /**
     * Retrieves a skill by slug with visibility enforcement.
     *
     * @param slug        the skill's unique slug within the organization
     * @param userDetails the authenticated user's details
     * @return the SkillEntity
     * @throws SkillNotFoundException if no skill with that slug exists or the user lacks visibility
     */
    SkillEntity getBySlug(String slug, AlexandriaUserDetails userDetails);

    /**
     * Lists versions for a skill in descending version number order.
     *
     * @param skillId     the skill's UUID
     * @param userDetails the authenticated user's details
     * @param pageable    pagination parameters
     * @return a page of SkillVersionEntity results
     * @throws SkillNotFoundException if the skill does not exist or the user lacks visibility
     */
    Page<SkillVersionEntity> listVersions(UUID skillId, AlexandriaUserDetails userDetails, Pageable pageable);

    /**
     * Retrieves a specific version of a skill.
     *
     * @param skillId       the skill's UUID
     * @param versionNumber the version number to retrieve
     * @param userDetails   the authenticated user's details
     * @return the SkillVersionEntity
     * @throws SkillNotFoundException if the skill or version does not exist or the user lacks visibility
     */
    SkillVersionEntity getVersion(UUID skillId, int versionNumber, AlexandriaUserDetails userDetails);
}
