package com.alexandria.config;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillAccessGrantRepository;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.user.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security bean for skill-scoped authorization checks.
 *
 * <p>Used in {@code @PreAuthorize} SpEL expressions to determine whether
 * the authenticated user is allowed to edit or view a specific skill.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")}
 * {@code @PreAuthorize("@skillSecurity.canView(#skillId, authentication)")}
 * </pre>
 */
@Component("skillSecurity")
@RequiredArgsConstructor
public class SkillSecurity {

    private final SkillRepository skillRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final SkillAccessGrantRepository skillAccessGrantRepository;

    /**
     * Returns {@code true} if the authenticated user can edit the skill.
     * Edit access is granted if the user is a member of the skill's owning team,
     * or has Catalog_Admin or Org_Admin role.
     *
     * @param skillId        the skill to check edit authority for
     * @param authentication the current authentication context
     * @return true if the user can edit the skill
     */
    public boolean canEdit(UUID skillId, Authentication authentication) {
        AlexandriaUserDetails user = extractUserDetails(authentication);
        if (user == null) {
            return false;
        }

        // Catalog_Admin and Org_Admin can always edit
        if (user.role().isAdmin()) {
            return true;
        }

        return skillRepository.findByIdAndOrganizationId(skillId, user.organizationId())
                .map(skill -> teamMemberRepository
                        .findByTeamIdAndUserId(skill.getOwnerTeamId(), user.userId())
                        .isPresent())
                .orElse(false);
    }

    /**
     * Returns {@code true} if the authenticated user can view the skill based on visibility rules.
     *
     * <p>Visibility logic:</p>
     * <ul>
     *   <li><strong>organization</strong>: all authenticated users in the same org</li>
     *   <li><strong>team</strong>: members of the owning team + the creator</li>
     *   <li><strong>private</strong>: only the creator</li>
     *   <li><strong>restricted</strong>: the creator + users with explicit access grants</li>
     *   <li><strong>Org_Admin</strong>: always has access regardless of visibility</li>
     * </ul>
     *
     * @param skillId        the skill to check view authority for
     * @param authentication the current authentication context
     * @return true if the user can view the skill
     */
    public boolean canView(UUID skillId, Authentication authentication) {
        AlexandriaUserDetails user = extractUserDetails(authentication);
        if (user == null) {
            return false;
        }

        // Org_Admin can always view everything
        if (user.role() == Role.Org_Admin) {
            return true;
        }

        return skillRepository.findByIdAndOrganizationId(skillId, user.organizationId())
                .map(skill -> isVisibleTo(skill, user))
                .orElse(false);
    }

    /**
     * Determines if a skill is visible to the given user based on its visibility setting.
     */
    private boolean isVisibleTo(SkillEntity skill, AlexandriaUserDetails user) {
        UUID userId = user.userId();

        return switch (skill.getVisibility()) {
            case "organization" -> true;
            case "team" -> skill.getCreatedBy().equals(userId)
                    || teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId).isPresent();
            case "private" -> skill.getCreatedBy().equals(userId);
            case "restricted" -> skill.getCreatedBy().equals(userId)
                    || skillAccessGrantRepository.existsBySkillIdAndUserId(skill.getId(), userId);
            default -> false;
        };
    }

    private AlexandriaUserDetails extractUserDetails(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AlexandriaUserDetails details) {
            return details;
        }
        return null;
    }
}
