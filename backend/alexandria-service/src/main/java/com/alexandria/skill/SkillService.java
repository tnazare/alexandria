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
     * Lists skills visible to the user with optional filters and full-text search.
     *
     * @param status      optional lifecycle status filter
     * @param visibility  optional visibility filter
     * @param riskLevel   optional risk level filter
     * @param teamId      optional owner team filter
     * @param taskType    optional taxonomy task_type tag value filter
     * @param language    optional taxonomy language tag value filter
     * @param framework   optional taxonomy framework tag value filter
     * @param tool        optional taxonomy tool tag value filter
     * @param searchQuery optional full-text search query
     * @param includeArchived whether to include archived skills
     * @param userDetails the authenticated user's details
     * @param pageable    pagination parameters
     * @return a page of visible SkillEntity results
     */
    Page<SkillEntity> list(String status, String visibility, String riskLevel, UUID teamId,
                           String taskType, String language, String framework, String tool,
                           String searchQuery, boolean includeArchived,
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

    /**
     * Updates a skill with partial parameters, applying versioning logic based on lifecycle status.
     *
     * <p>If the skill is in Draft status, changes are applied in-place on the current version.
     * If the skill is in Published/Team_Approved/Company_Approved status, a new version is created
     * and the lifecycle is reverted to Draft.</p>
     *
     * @param skillId     the skill's UUID
     * @param params      the update parameters (only non-null fields are applied)
     * @param userDetails the authenticated user's details
     * @return the updated SkillEntity
     * @throws SkillNotFoundException      if the skill does not exist in the user's organization
     * @throws SkillAccessDeniedException  if the user lacks edit authority
     * @throws SkillConflictException      if the skill lifecycle status prevents editing
     */
    SkillEntity update(UUID skillId, UpdateParams params, AlexandriaUserDetails userDetails);

    /**
     * Publishes a skill by validating required metadata and transitioning lifecycle to "Published".
     *
     * <p>The skill must be in "Draft" status. Required metadata includes:
     * at least one task_type tag, visibility set, at least one supported tool,
     * risk_level set, applicability non-empty, and at least one example.</p>
     *
     * @param skillId     the skill's UUID
     * @param userDetails the authenticated user's details
     * @return the published SkillEntity
     * @throws SkillNotFoundException         if the skill does not exist in the user's organization
     * @throws SkillAccessDeniedException     if the user lacks edit authority
     * @throws SkillConflictException         if the skill is not in Draft status
     * @throws PublishValidationException     if required metadata is missing (HTTP 422)
     */
    SkillEntity publish(UUID skillId, AlexandriaUserDetails userDetails);

    /**
     * Changes the visibility of a skill.
     *
     * @param skillId     the skill's UUID
     * @param visibility  the new visibility value (private, team, organization, or restricted)
     * @param userDetails the authenticated user's details
     * @return the updated SkillEntity
     * @throws SkillNotFoundException      if the skill does not exist in the user's organization
     * @throws SkillAccessDeniedException  if the user lacks edit authority
     * @throws IllegalArgumentException    if the visibility value is invalid
     */
    SkillEntity changeVisibility(UUID skillId, String visibility, AlexandriaUserDetails userDetails);

    /**
     * Deletes a skill and all associated data (access grants, tags, variants, versions).
     *
     * <p>Only skills in "Draft" lifecycle status can be deleted.</p>
     *
     * @param skillId     the skill's UUID
     * @param userDetails the authenticated user's details
     * @throws SkillNotFoundException      if the skill does not exist in the user's organization
     * @throws SkillAccessDeniedException  if the user lacks edit authority
     * @throws SkillConflictException      if the skill is not in Draft status
     */
    void delete(UUID skillId, AlexandriaUserDetails userDetails);
}
