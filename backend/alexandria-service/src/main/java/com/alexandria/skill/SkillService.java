package com.alexandria.skill;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;

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
}
