package com.alexandria.config;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.user.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security bean for team-member-scoped authorization checks.
 *
 * <p>Used in {@code @PreAuthorize} SpEL expressions to determine whether
 * the authenticated user is allowed to manage a specific team's membership.
 * Access is granted if the user is an Org_Admin or is an owner of the team.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code @PreAuthorize("@teamSecurity.isOwnerOrAdmin(#teamId, authentication)")}
 * </pre>
 */
@Component("teamSecurity")
@RequiredArgsConstructor
public class TeamSecurity {

    private final TeamMemberRepository teamMemberRepository;

    /**
     * Returns {@code true} if the authenticated user has Org_Admin role
     * or is an owner of the specified team.
     *
     * @param teamId         the team to check ownership for
     * @param authentication the current authentication context
     * @return true if the user is an org admin or team owner
     */
    public boolean isOwnerOrAdmin(UUID teamId, Authentication authentication) {
        AlexandriaUserDetails userDetails = extractUserDetails(authentication);
        if (userDetails == null) {
            return false;
        }

        // Org_Admin has access to everything
        if (userDetails.role() == Role.Org_Admin) {
            return true;
        }

        // Check if the user is an owner of this team
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userDetails.userId())
                .map(member -> "owner".equals(member.getTeamRole()))
                .orElse(false);
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
