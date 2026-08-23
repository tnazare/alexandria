package com.alexandria.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authentication token that carries {@link AlexandriaUserDetails} as the principal.
 *
 * <p>This token is created by the {@code UserProvisioningFilter} after successful
 * JWT validation and user resolution. It provides the user's role-based
 * {@link GrantedAuthority} instances to Spring Security's authorization layer,
 * enabling SpEL expressions like {@code hasRole('Org_Admin')} to work correctly.</p>
 */
public class AlexandriaAuthentication extends AbstractAuthenticationToken {

    private final AlexandriaUserDetails principal;

    public AlexandriaAuthentication(AlexandriaUserDetails principal) {
        super(principal.getAuthorities());
        this.principal = principal;
        setAuthenticated(true);
    }

    public AlexandriaAuthentication(AlexandriaUserDetails principal,
                                    Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AlexandriaUserDetails getPrincipal() {
        return principal;
    }
}
