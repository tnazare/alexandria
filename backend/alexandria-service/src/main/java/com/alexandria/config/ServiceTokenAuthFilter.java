package com.alexandria.config;

import com.alexandria.agent.ServiceTokenRepository;
import com.alexandria.agent.entity.ServiceTokenEntity;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.user.entity.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Authenticates non-interactive agent callers presenting a service token (Requirement 9).
 *
 * <p>Runs before the OAuth2 resource server's bearer-token filter. When the {@code Authorization}
 * header carries a bearer value with the service-token prefix ({@code alx_}), this filter hashes it
 * (SHA-256), looks it up, validates it is not revoked or expired, and installs an
 * {@link AlexandriaAuthentication} carrying a read-only agent principal scoped to the token's
 * organization. Otherwise it does nothing and lets the JWT path handle the request unchanged, so
 * existing OIDC authentication is untouched.</p>
 *
 * <p>The agent principal uses role {@link Role#Skill_Consumer} — sufficient to resolve/fetch, but
 * structurally unable to author, review, approve, or administer (Requirement 9.6). The token id is
 * used as the principal's user id for usage/audit attribution.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    /** Non-secret prefix identifying an Alexandria service token. */
    public static final String TOKEN_PREFIX = "alx_";
    private static final String BEARER = "Bearer ";

    private final ServiceTokenRepository serviceTokenRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractServiceToken(request);
        if (token == null) {
            // Not a service token — let the JWT resource server handle it.
            filterChain.doFilter(request, response);
            return;
        }

        // Already authenticated (e.g. filter re-entry) — do not override.
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                && SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<ServiceTokenEntity> match = serviceTokenRepository.findByTokenHash(sha256(token));
        if (match.isEmpty() || !isUsable(match.get())) {
            // Invalid/revoked/expired service token: leave the context unauthenticated. The
            // authorizeHttpRequests(anyRequest().authenticated()) rule then yields 401.
            filterChain.doFilter(request, response);
            return;
        }

        ServiceTokenEntity serviceToken = match.get();
        serviceToken.setLastUsedAt(Instant.now());
        try {
            serviceTokenRepository.save(serviceToken);
        } catch (Exception ex) {
            // last_used_at is best-effort telemetry; never fail auth on its account.
            log.debug("Failed to update service token last_used_at: {}", ex.getMessage());
        }

        AlexandriaUserDetails principal = new AlexandriaUserDetails(
                serviceToken.getId(),
                serviceToken.getOrganizationId(),
                Role.Skill_Consumer,
                "service-token:" + serviceToken.getTokenPrefix(),
                true);

        SecurityContextHolder.getContext().setAuthentication(new AlexandriaAuthentication(principal));
        filterChain.doFilter(request, response);
    }

    /** Returns the raw service token from a Bearer header, or null when not a service token. */
    private String extractServiceToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String value = header.substring(BEARER.length()).trim();
        return value.startsWith(TOKEN_PREFIX) ? value : null;
    }

    private static boolean isUsable(ServiceTokenEntity token) {
        if (token.getRevokedAt() != null) {
            return false;
        }
        return token.getExpiresAt() == null || token.getExpiresAt().isAfter(Instant.now());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
