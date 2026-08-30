package com.alexandria.config;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.audit.AuditEventRepository;
import com.alexandria.audit.entity.AuditEventEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Filter that runs after JWT authentication to provision users and enrich the SecurityContext.
 *
 * <p>Extracts the subject and email claims from the validated JWT, validates the email format,
 * looks up or creates the user record, and replaces the SecurityContext authentication with
 * an enriched principal containing the Alexandria user details.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class UserProvisioningFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;
    private final OrganizationProperties organizationProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();

        // Skip if not authenticated via JWT (e.g., unauthenticated public endpoints)
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            filterChain.doFilter(request, response);
            return;
        }

        var jwt = jwtAuth.getToken();
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");

        // Validate email claim format
        if (!isValidEmail(email)) {
            log.warn("Request rejected: missing or invalid email claim for subject '{}'", subject);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    """
                    {"error":"FORBIDDEN","message":"Missing or invalid email claim"}"""
            );
            return;
        }

        UUID orgId = organizationProperties.defaultId();

        try {
            // Look up or create user
            UserEntity user = userRepository.findByOrganizationIdAndExternalSubject(orgId, subject)
                    .orElseGet(() -> provisionUser(orgId, subject, email));

            // Build enriched principal
            Role role = Role.valueOf(user.getRole());
            var userDetails = new AlexandriaUserDetails(
                    user.getId(), orgId, role, user.getEmail()
            );

            // Replace security context with enriched authentication.
            // Use the canonical com.alexandria.auth.AlexandriaAuthentication so that the
            // controllers' `instanceof AlexandriaAuthentication` checks match at runtime.
            var enrichedAuth = new AlexandriaAuthentication(userDetails);
            SecurityContextHolder.getContext().setAuthentication(enrichedAuth);

            filterChain.doFilter(request, response);

        } catch (Exception ex) {
            log.error("User provisioning failed for subject '{}': {}", subject, ex.getMessage(), ex);
            recordProvisioningFailure(orgId, subject, ex.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write(
                    """
                    {"error":"INTERNAL_ERROR","message":"Account creation failed"}"""
            );
        }
    }

    private UserEntity provisionUser(UUID orgId, String subject, String email) {
        var user = new UserEntity();
        user.setOrganizationId(orgId);
        user.setExternalSubject(subject);
        user.setEmail(email);
        user.setRole(Role.Skill_Consumer.name());
        user.setEnabled(true);

        UserEntity savedUser = userRepository.save(user);

        // Record successful provisioning audit event
        recordProvisioningSuccess(orgId, savedUser.getId(), email, subject);

        log.info("Auto-provisioned user '{}' with email '{}' in organization '{}'",
                subject, email, orgId);

        return savedUser;
    }

    private void recordProvisioningSuccess(UUID orgId, UUID userId, String email, String subject) {
        try {
            var event = new AuditEventEntity();
            event.setOrganizationId(orgId);
            event.setActorUserId(userId);
            event.setAction("user.auto_provisioned");
            event.setTargetType("user");
            event.setTargetId(userId);
            event.setMetadata(Map.of("email", email, "subject", subject));
            auditEventRepository.save(event);
        } catch (Exception ex) {
            log.error("Failed to record provisioning audit event for subject '{}': {}",
                    subject, ex.getMessage(), ex);
        }
    }

    private void recordProvisioningFailure(UUID orgId, String subject, String errorReason) {
        try {
            var event = new AuditEventEntity();
            event.setOrganizationId(orgId);
            event.setAction("user.auto_provision_failed");
            event.setTargetType("user");
            event.setTargetId(new UUID(0, 0)); // placeholder since user was not created
            event.setMetadata(Map.of("subject", subject, "error_reason", errorReason != null ? errorReason : "unknown"));
            auditEventRepository.save(event);
        } catch (Exception ex) {
            log.error("Failed to record provisioning failure audit event for subject '{}': {}",
                    subject, ex.getMessage(), ex);
        }
    }

    /**
     * Validates email format: must contain @ with non-empty local part and domain.
     */
    static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int atIndex = email.indexOf('@');
        // Must have @ that is not the first or last character
        return atIndex > 0 && atIndex < email.length() - 1;
    }

}
