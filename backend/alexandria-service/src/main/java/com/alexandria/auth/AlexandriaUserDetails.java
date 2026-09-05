package com.alexandria.auth;

import com.alexandria.user.entity.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Custom security principal containing Alexandria-specific user details.
 *
 * <p>Wraps the authenticated user's identity (userId, organizationId, role, email)
 * and provides a single {@link GrantedAuthority} with the ROLE_ prefix so that
 * Spring Security's {@code hasRole()} and {@code hasAnyRole()} SpEL expressions
 * work correctly.</p>
 */
public record AlexandriaUserDetails(
        UUID userId,
        UUID organizationId,
        Role role,
        String email,
        boolean serviceToken
) {

    /**
     * Convenience constructor for interactive (user) principals. Defaults {@code serviceToken} to
     * false so existing OIDC/user call sites are unchanged.
     */
    public AlexandriaUserDetails(UUID userId, UUID organizationId, Role role, String email) {
        this(userId, organizationId, role, email, false);
    }

    /**
     * Returns the authorities for this user — a single GrantedAuthority
     * with the ROLE_ prefix followed by the role name.
     */
    public Collection<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /**
     * Returns the user id to attribute usage/audit events to, or null for a service-token principal
     * (whose id references no {@code users} row and would violate the actor foreign key).
     */
    public UUID actorUserId() {
        return serviceToken ? null : userId;
    }
}
