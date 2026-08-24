package com.alexandria.integration;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.UserProvisioningFilter;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for RBAC and endpoint access control.
 *
 * <p>Validates Properties 5, 6, 7, and 8 from the design document:</p>
 * <ul>
 *   <li>Property 5: Organization data scoping — user in org A cannot see org B data</li>
 *   <li>Property 6: Org_Admin can access all endpoints</li>
 *   <li>Property 7: Read_Only_Auditor cannot mutate</li>
 *   <li>Property 8: Skill_Consumer/Skill_Author/Reviewer denied admin endpoints</li>
 * </ul>
 *
 * <p><b>Requirements: 6.1, 6.4, 6.5, 6.10, 6.11</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacEndpointAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID orgAId;
    private UUID orgBId;
    private Map<Role, UUID> orgAUserIds;
    private UUID orgBUserId;

    @BeforeAll
    void setupTestData() {
        // Create two organizations for org-scoping tests
        var orgA = new OrganizationEntity();
        orgA.setName("Organization A");
        orgA.setSlug("org-a-" + UUID.randomUUID());
        orgA = organizationRepository.saveAndFlush(orgA);
        orgAId = orgA.getId();

        var orgB = new OrganizationEntity();
        orgB.setName("Organization B");
        orgB.setSlug("org-b-" + UUID.randomUUID());
        orgB = organizationRepository.saveAndFlush(orgB);
        orgBId = orgB.getId();

        // Create a user for each role in Org A
        orgAUserIds = new java.util.EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            var user = new UserEntity();
            user.setOrganizationId(orgAId);
            user.setExternalSubject("subject-" + role.name().toLowerCase());
            user.setEmail(role.name().toLowerCase().replace("_", "") + "@orga.com");
            user.setRole(role.name());
            user.setEnabled(true);
            user = userRepository.saveAndFlush(user);
            orgAUserIds.put(role, user.getId());
        }

        // Create a user in Org B (Org_Admin) for scoping tests
        var orgBUser = new UserEntity();
        orgBUser.setOrganizationId(orgBId);
        orgBUser.setExternalSubject("subject-orgb-admin");
        orgBUser.setEmail("admin@orgb.com");
        orgBUser.setRole(Role.Org_Admin.name());
        orgBUser.setEnabled(true);
        orgBUser = userRepository.saveAndFlush(orgBUser);
        orgBUserId = orgBUser.getId();
    }

    // ========================================================================
    // Property 6: Org_Admin full access
    // ========================================================================

    @Nested
    @DisplayName("Property 6: Org_Admin can access all endpoints")
    class OrgAdminFullAccess {

        @Test
        @DisplayName("Org_Admin can GET /api/me")
        void orgAdmin_canAccessMe() throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Org_Admin can GET /api/users")
        void orgAdmin_canListUsers() throws Exception {
            mockMvc.perform(get("/api/users")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Org_Admin can PATCH /api/users/{userId}/role")
        void orgAdmin_canChangeRole() throws Exception {
            UUID targetUserId = orgAUserIds.get(Role.Skill_Consumer);
            mockMvc.perform(patch("/api/users/{userId}/role", targetUserId)
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"Skill_Author"}"""))
                    .andExpect(status().isOk());

            // Restore original role
            mockMvc.perform(patch("/api/users/{userId}/role", targetUserId)
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"Skill_Consumer"}"""))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Org_Admin can POST /api/teams")
        void orgAdmin_canCreateTeam() throws Exception {
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Admin Test Team","description":"test"}"""))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Org_Admin can GET /api/teams")
        void orgAdmin_canListTeams() throws Exception {
            mockMvc.perform(get("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Org_Admin can GET /api/audit-events")
        void orgAdmin_canViewAuditEvents() throws Exception {
            mockMvc.perform(get("/api/audit-events")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin))))
                    .andExpect(status().isOk());
        }
    }

    // ========================================================================
    // Property 7: Read_Only_Auditor cannot mutate
    // ========================================================================

    @Nested
    @DisplayName("Property 7: Read_Only_Auditor cannot mutate")
    class ReadOnlyAuditorCannotMutate {

        @Test
        @DisplayName("Read_Only_Auditor can GET /api/me")
        void readOnlyAuditor_canAccessMe() throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Read_Only_Auditor can GET /api/audit-events")
        void readOnlyAuditor_canViewAuditEvents() throws Exception {
            mockMvc.perform(get("/api/audit-events")
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Read_Only_Auditor cannot PATCH /api/users/{userId}/role")
        void readOnlyAuditor_cannotChangeRole() throws Exception {
            UUID targetUserId = orgAUserIds.get(Role.Skill_Consumer);
            mockMvc.perform(patch("/api/users/{userId}/role", targetUserId)
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"Skill_Author"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Read_Only_Auditor cannot POST /api/teams")
        void readOnlyAuditor_cannotCreateTeam() throws Exception {
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Auditor Team","description":"test"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Read_Only_Auditor cannot POST /api/teams/{teamId}/members")
        void readOnlyAuditor_cannotAddMembers() throws Exception {
            UUID fakeTeamId = UUID.randomUUID();
            mockMvc.perform(post("/api/teams/{teamId}/members", fakeTeamId)
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":"%s","teamRole":"member"}""".formatted(orgAUserIds.get(Role.Skill_Consumer))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Read_Only_Auditor cannot DELETE /api/teams/{teamId}/members/{userId}")
        void readOnlyAuditor_cannotRemoveMembers() throws Exception {
            UUID fakeTeamId = UUID.randomUUID();
            UUID fakeUserId = UUID.randomUUID();
            mockMvc.perform(delete("/api/teams/{teamId}/members/{userId}", fakeTeamId, fakeUserId)
                            .with(authenticatedAs(Role.Read_Only_Auditor, orgAId, orgAUserIds.get(Role.Read_Only_Auditor))))
                    .andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // Property 8: Restricted roles denied admin endpoints
    // ========================================================================

    @Nested
    @DisplayName("Property 8: Skill_Consumer/Skill_Author/Reviewer denied admin endpoints")
    class RestrictedRolesDeniedAdminEndpoints {

        static Stream<Arguments> restrictedRoles() {
            return Stream.of(
                    Arguments.of(Role.Skill_Consumer),
                    Arguments.of(Role.Skill_Author),
                    Arguments.of(Role.Reviewer)
            );
        }

        @ParameterizedTest(name = "{0} cannot GET /api/users")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles cannot list users")
        void restrictedRole_cannotListUsers(Role role) throws Exception {
            mockMvc.perform(get("/api/users")
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} cannot PATCH /api/users/{userId}/role")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles cannot change roles")
        void restrictedRole_cannotChangeRole(Role role) throws Exception {
            UUID targetUserId = orgAUserIds.get(Role.Read_Only_Auditor);
            mockMvc.perform(patch("/api/users/{userId}/role", targetUserId)
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"Skill_Author"}"""))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} cannot POST /api/teams")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles cannot create teams")
        void restrictedRole_cannotCreateTeam(Role role) throws Exception {
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Restricted Team","description":"test"}"""))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} cannot GET /api/audit-events")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles cannot view audit events")
        void restrictedRole_cannotViewAuditEvents(Role role) throws Exception {
            mockMvc.perform(get("/api/audit-events")
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} can GET /api/me (any authenticated)")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles can still access /api/me")
        void restrictedRole_canAccessMe(Role role) throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role))))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0} can GET /api/teams (any authenticated)")
        @MethodSource("restrictedRoles")
        @DisplayName("Restricted roles can list teams")
        void restrictedRole_canListTeams(Role role) throws Exception {
            mockMvc.perform(get("/api/teams")
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role))))
                    .andExpect(status().isOk());
        }
    }

    // ========================================================================
    // Property 5: Organization data scoping
    // ========================================================================

    @Nested
    @DisplayName("Property 5: Organization data scoping")
    class OrganizationDataScoping {

        @Test
        @DisplayName("User in org B cannot see org A users via GET /api/users")
        void orgBAdmin_cannotSeeOrgAUsers() throws Exception {
            // Org B admin lists users - should only see org B users, not org A
            var result = mockMvc.perform(get("/api/users")
                            .with(authenticatedAs(Role.Org_Admin, orgBId, orgBUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            // Org B should not contain any org A user emails
            for (Role role : Role.values()) {
                String orgAEmail = role.name().toLowerCase().replace("_", "") + "@orga.com";
                org.assertj.core.api.Assertions.assertThat(body).doesNotContain(orgAEmail);
            }
        }

        @Test
        @DisplayName("User in org B cannot see org A teams via GET /api/teams")
        void orgBAdmin_cannotSeeOrgATeams() throws Exception {
            // First create a team in org A
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgAId, orgAUserIds.get(Role.Org_Admin)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Org A Scoping Team","description":"for scoping test"}"""))
                    .andExpect(status().isCreated());

            // Org B admin lists teams - should not see org A's team
            var result = mockMvc.perform(get("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgBId, orgBUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Org A Scoping Team");
        }

        @Test
        @DisplayName("User in org B cannot see org A audit events via GET /api/audit-events")
        void orgBAdmin_cannotSeeOrgAAuditEvents() throws Exception {
            var result = mockMvc.perform(get("/api/audit-events")
                            .with(authenticatedAs(Role.Org_Admin, orgBId, orgBUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            // Org B should not contain any org A user IDs as actors
            for (UUID userId : orgAUserIds.values()) {
                org.assertj.core.api.Assertions.assertThat(body).doesNotContain(userId.toString());
            }
        }
    }

    // ========================================================================
    // Parameterized role × endpoint matrix
    // ========================================================================

    @Nested
    @DisplayName("Role × Endpoint access matrix (parameterized)")
    class RoleEndpointMatrix {

        /**
         * Provides test cases: (role, method, path, expectedAllowed).
         * This covers the full matrix of roles against admin-protected endpoints.
         */
        static Stream<Arguments> roleEndpointMatrix() {
            return Stream.of(
                    // Org_Admin - allowed everywhere
                    Arguments.of(Role.Org_Admin, "GET", "/api/users", true),
                    Arguments.of(Role.Org_Admin, "GET", "/api/audit-events", true),

                    // Catalog_Admin - allowed to create teams
                    Arguments.of(Role.Catalog_Admin, "GET", "/api/users", false),
                    Arguments.of(Role.Catalog_Admin, "GET", "/api/audit-events", false),

                    // Read_Only_Auditor - allowed audit, denied admin
                    Arguments.of(Role.Read_Only_Auditor, "GET", "/api/users", false),
                    Arguments.of(Role.Read_Only_Auditor, "GET", "/api/audit-events", true),

                    // Team_Owner - denied admin
                    Arguments.of(Role.Team_Owner, "GET", "/api/users", false),
                    Arguments.of(Role.Team_Owner, "GET", "/api/audit-events", false),

                    // Reviewer - denied admin
                    Arguments.of(Role.Reviewer, "GET", "/api/users", false),
                    Arguments.of(Role.Reviewer, "GET", "/api/audit-events", false),

                    // Skill_Author - denied admin
                    Arguments.of(Role.Skill_Author, "GET", "/api/users", false),
                    Arguments.of(Role.Skill_Author, "GET", "/api/audit-events", false),

                    // Skill_Consumer - denied admin
                    Arguments.of(Role.Skill_Consumer, "GET", "/api/users", false),
                    Arguments.of(Role.Skill_Consumer, "GET", "/api/audit-events", false)
            );
        }

        @ParameterizedTest(name = "{0} {1} {2} -> allowed={3}")
        @MethodSource("roleEndpointMatrix")
        @DisplayName("Role-endpoint access check")
        void verifyAccess(Role role, String method, String path, boolean expectedAllowed) throws Exception {
            var requestBuilder = switch (method) {
                case "GET" -> get(path);
                case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
                case "PATCH" -> patch(path).contentType(MediaType.APPLICATION_JSON).content("{}");
                case "DELETE" -> delete(path);
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            };

            var result = mockMvc.perform(requestBuilder
                            .with(authenticatedAs(role, orgAId, orgAUserIds.get(role))))
                    .andReturn();

            int statusCode = result.getResponse().getStatus();
            if (expectedAllowed) {
                org.assertj.core.api.Assertions.assertThat(statusCode)
                        .as("Expected %s to be allowed %s %s but got %d", role, method, path, statusCode)
                        .isNotEqualTo(403);
            } else {
                org.assertj.core.api.Assertions.assertThat(statusCode)
                        .as("Expected %s to be denied %s %s but got %d", role, method, path, statusCode)
                        .isEqualTo(403);
            }
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Creates a RequestPostProcessor that sets up the SecurityContext with an
     * AlexandriaAuthentication for the given role, organization, and user ID.
     *
     * <p>This bypasses the UserProvisioningFilter (which requires a real JWT) and
     * directly injects the enriched authentication object that the filter would produce.</p>
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs(
            Role role, UUID organizationId, UUID userId) {
        return new AlexandriaAuthPostProcessor(role, organizationId, userId);
    }

    /**
     * Custom RequestPostProcessor that injects AlexandriaAuthentication into the
     * SecurityContext before the request is dispatched to the servlet.
     */
    private static class AlexandriaAuthPostProcessor
            implements org.springframework.test.web.servlet.request.RequestPostProcessor {

        private final Role role;
        private final UUID organizationId;
        private final UUID userId;

        AlexandriaAuthPostProcessor(Role role, UUID organizationId, UUID userId) {
            this.role = role;
            this.organizationId = organizationId;
            this.userId = userId;
        }

        @Override
        public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
            var userDetails = new AlexandriaUserDetails(userId, organizationId, role,
                    role.name().toLowerCase() + "@test.com");

            // Create a minimal JWT for the JwtAuthenticationToken
            Jwt jwt = Jwt.withTokenValue("mock-token")
                    .header("alg", "RS256")
                    .claim("sub", "subject-" + role.name().toLowerCase())
                    .claim("email", role.name().toLowerCase() + "@test.com")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            var jwtAuth = new JwtAuthenticationToken(jwt);
            var alexandriaAuth = new UserProvisioningFilter.AlexandriaAuthentication(jwtAuth, userDetails);

            // Set the SecurityContext for this request
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(alexandriaAuth);

            request.setAttribute(
                    "org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT",
                    securityContext);

            // Also use Spring Security test support mechanism
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .securityContext(securityContext).postProcessRequest(request);

            return request;
        }
    }
}
