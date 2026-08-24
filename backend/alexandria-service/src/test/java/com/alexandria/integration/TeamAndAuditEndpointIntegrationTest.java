package com.alexandria.integration;

import com.alexandria.audit.AuditEventRepository;
import com.alexandria.audit.AuditService;
import com.alexandria.audit.entity.AuditEventEntity;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.UserProvisioningFilter;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for team management and audit event endpoints.
 *
 * <p>Validates:</p>
 * <ul>
 *   <li>Team creation: valid name → 201, duplicate slug → 409, invalid role → 403</li>
 *   <li>Member management: add → 201, remove → 204, last owner removal → 409, not found → 404</li>
 *   <li>Audit event listing: filters combine with AND logic, descending order</li>
 *   <li>Property 12: /api/me returns correct user profile</li>
 * </ul>
 *
 * <p><b>Requirements: 5.1–5.12, 7.3, 7.4, 8.4</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamAndAuditEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditService auditService;

    private UUID orgId;
    private UUID adminUserId;
    private UUID memberUserId;
    private UUID secondMemberUserId;
    private UUID skillConsumerUserId;

    @BeforeAll
    void setupTestData() {
        var org = new OrganizationEntity();
        org.setName("Team Test Org");
        org.setSlug("team-test-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        orgId = org.getId();

        // Org_Admin user
        var adminUser = new UserEntity();
        adminUser.setOrganizationId(orgId);
        adminUser.setExternalSubject("subject-admin-" + UUID.randomUUID());
        adminUser.setEmail("admin@teamtest.com");
        adminUser.setDisplayName("Admin User");
        adminUser.setRole(Role.Org_Admin.name());
        adminUser.setEnabled(true);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminUserId = adminUser.getId();

        // Regular member user (Skill_Author)
        var memberUser = new UserEntity();
        memberUser.setOrganizationId(orgId);
        memberUser.setExternalSubject("subject-member-" + UUID.randomUUID());
        memberUser.setEmail("member@teamtest.com");
        memberUser.setDisplayName("Member User");
        memberUser.setRole(Role.Skill_Author.name());
        memberUser.setEnabled(true);
        memberUser = userRepository.saveAndFlush(memberUser);
        memberUserId = memberUser.getId();

        // Second member user (Skill_Consumer)
        var secondMember = new UserEntity();
        secondMember.setOrganizationId(orgId);
        secondMember.setExternalSubject("subject-second-" + UUID.randomUUID());
        secondMember.setEmail("second@teamtest.com");
        secondMember.setDisplayName("Second User");
        secondMember.setRole(Role.Skill_Consumer.name());
        secondMember.setEnabled(true);
        secondMember = userRepository.saveAndFlush(secondMember);
        secondMemberUserId = secondMember.getId();

        // Skill_Consumer user (restricted role)
        var consumer = new UserEntity();
        consumer.setOrganizationId(orgId);
        consumer.setExternalSubject("subject-consumer-" + UUID.randomUUID());
        consumer.setEmail("consumer@teamtest.com");
        consumer.setDisplayName("Consumer User");
        consumer.setRole(Role.Skill_Consumer.name());
        consumer.setEnabled(true);
        consumer = userRepository.saveAndFlush(consumer);
        skillConsumerUserId = consumer.getId();
    }

    // ========================================================================
    // Team Creation Tests
    // ========================================================================

    @Nested
    @DisplayName("Team Creation")
    class TeamCreation {

        @Test
        @DisplayName("Valid team creation returns 201 with correct response")
        void validTeamCreation_returns201() throws Exception {
            var result = mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Engineering Team","description":"The engineering team"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Engineering Team"))
                    .andExpect(jsonPath("$.slug").value("engineering-team"))
                    .andExpect(jsonPath("$.description").value("The engineering team"))
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.memberCount").value(1))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andReturn();
        }

        @Test
        @DisplayName("Duplicate slug returns 409 Conflict")
        void duplicateSlug_returns409() throws Exception {
            // Create first team
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Duplicate Team","description":"first"}"""))
                    .andExpect(status().isCreated());

            // Attempt to create team with same name (same slug)
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Duplicate Team","description":"second"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Team name already taken"));
        }

        @Test
        @DisplayName("Names differing only in case produce same slug and 409")
        void caseVariation_sameSlug_returns409() throws Exception {
            // Create team
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Case Test","description":"first"}"""))
                    .andExpect(status().isCreated());

            // Same name different case produces same slug
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"CASE TEST","description":"second"}"""))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Skill_Consumer cannot create teams (403)")
        void invalidRole_returns403() throws Exception {
            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Skill_Consumer, orgId, skillConsumerUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Unauthorized Team","description":"should fail"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Catalog_Admin can create teams (201)")
        void catalogAdmin_canCreateTeam() throws Exception {
            // Create a Catalog_Admin user
            var catalogAdmin = new UserEntity();
            catalogAdmin.setOrganizationId(orgId);
            catalogAdmin.setExternalSubject("subject-catalog-admin-" + UUID.randomUUID());
            catalogAdmin.setEmail("catalogadmin@teamtest.com");
            catalogAdmin.setRole(Role.Catalog_Admin.name());
            catalogAdmin.setEnabled(true);
            catalogAdmin = userRepository.saveAndFlush(catalogAdmin);

            mockMvc.perform(post("/api/teams")
                            .with(authenticatedAs(Role.Catalog_Admin, orgId, catalogAdmin.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Catalog Admin Team","description":"created by catalog admin"}"""))
                    .andExpect(status().isCreated());
        }
    }

    // ========================================================================
    // Member Management Tests
    // ========================================================================

    @Nested
    @DisplayName("Member Management")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MemberManagement {

        private UUID teamId;

        @BeforeAll
        void createTeamForMemberTests() {
            // Create a team directly via the repository to avoid test coupling
            var team = new TeamEntity();
            team.setOrganizationId(orgId);
            team.setName("Member Test Team");
            team.setSlug("member-test-team-" + UUID.randomUUID().toString().substring(0, 8));
            team.setDescription("Team for member management tests");
            team.setCreatedBy(adminUserId);
            team = teamRepository.saveAndFlush(team);
            teamId = team.getId();

            // Add admin as owner
            var ownerMember = new TeamMemberEntity();
            ownerMember.setTeamId(teamId);
            ownerMember.setUserId(adminUserId);
            ownerMember.setTeamRole("owner");
            teamMemberRepository.saveAndFlush(ownerMember);
        }

        @Test
        @DisplayName("Add member returns 201 with correct response")
        void addMember_returns201() throws Exception {
            mockMvc.perform(post("/api/teams/{teamId}/members", teamId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":"%s","teamRole":"member"}""".formatted(memberUserId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(memberUserId.toString()))
                    .andExpect(jsonPath("$.email").value("member@teamtest.com"))
                    .andExpect(jsonPath("$.teamRole").value("member"))
                    .andExpect(jsonPath("$.joinedAt").isNotEmpty());
        }

        @Test
        @DisplayName("Remove member returns 204")
        void removeMember_returns204() throws Exception {
            // First add a member to remove
            var member = new TeamMemberEntity();
            member.setTeamId(teamId);
            member.setUserId(secondMemberUserId);
            member.setTeamRole("member");
            teamMemberRepository.saveAndFlush(member);

            mockMvc.perform(delete("/api/teams/{teamId}/members/{userId}", teamId, secondMemberUserId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Removing last owner returns 409")
        void removeLastOwner_returns409() throws Exception {
            // Create a team with only one owner
            var singleOwnerTeam = new TeamEntity();
            singleOwnerTeam.setOrganizationId(orgId);
            singleOwnerTeam.setName("Single Owner Team");
            singleOwnerTeam.setSlug("single-owner-team-" + UUID.randomUUID().toString().substring(0, 8));
            singleOwnerTeam.setDescription("Only one owner");
            singleOwnerTeam.setCreatedBy(adminUserId);
            singleOwnerTeam = teamRepository.saveAndFlush(singleOwnerTeam);

            var ownerMember = new TeamMemberEntity();
            ownerMember.setTeamId(singleOwnerTeam.getId());
            ownerMember.setUserId(adminUserId);
            ownerMember.setTeamRole("owner");
            teamMemberRepository.saveAndFlush(ownerMember);

            mockMvc.perform(delete("/api/teams/{teamId}/members/{userId}",
                            singleOwnerTeam.getId(), adminUserId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Team must have at least one owner"));
        }

        @Test
        @DisplayName("Remove non-existent member returns 404")
        void removeNonExistentMember_returns404() throws Exception {
            UUID nonExistentUserId = UUID.randomUUID();
            mockMvc.perform(delete("/api/teams/{teamId}/members/{userId}", teamId, nonExistentUserId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Add member to non-existent team returns 404")
        void addMemberToNonExistentTeam_returns404() throws Exception {
            UUID fakeTeamId = UUID.randomUUID();
            mockMvc.perform(post("/api/teams/{teamId}/members", fakeTeamId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":"%s","teamRole":"member"}""".formatted(memberUserId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Add non-existent user as member returns 404")
        void addNonExistentUser_returns404() throws Exception {
            UUID fakeUserId = UUID.randomUUID();
            mockMvc.perform(post("/api/teams/{teamId}/members", teamId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":"%s","teamRole":"member"}""".formatted(fakeUserId)))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    // Audit Event Listing Tests
    // ========================================================================

    @Nested
    @DisplayName("Audit Event Listing")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AuditEventListing {

        private UUID auditActorId;

        @BeforeAll
        void createAuditEvents() {
            // Create a dedicated user for audit tests
            var auditUser = new UserEntity();
            auditUser.setOrganizationId(orgId);
            auditUser.setExternalSubject("subject-audit-actor-" + UUID.randomUUID());
            auditUser.setEmail("auditactor@teamtest.com");
            auditUser.setRole(Role.Org_Admin.name());
            auditUser.setEnabled(true);
            auditUser = userRepository.saveAndFlush(auditUser);
            auditActorId = auditUser.getId();

            // Create audit events with different actions and target types
            Instant baseTime = Instant.now().minus(10, ChronoUnit.MINUTES);

            createAuditEvent(orgId, auditActorId, "team.created", "team",
                    UUID.randomUUID(), baseTime.plus(1, ChronoUnit.SECONDS));
            createAuditEvent(orgId, auditActorId, "team.created", "team",
                    UUID.randomUUID(), baseTime.plus(2, ChronoUnit.SECONDS));
            createAuditEvent(orgId, auditActorId, "team_member.added", "team_member",
                    UUID.randomUUID(), baseTime.plus(3, ChronoUnit.SECONDS));
            createAuditEvent(orgId, adminUserId, "user.role_changed", "user",
                    UUID.randomUUID(), baseTime.plus(4, ChronoUnit.SECONDS));
            createAuditEvent(orgId, adminUserId, "team_member.removed", "team_member",
                    UUID.randomUUID(), baseTime.plus(5, ChronoUnit.SECONDS));
        }

        private void createAuditEvent(UUID orgId, UUID actorId, String action,
                                       String targetType, UUID targetId, Instant createdAt) {
            var event = new AuditEventEntity();
            event.setOrganizationId(orgId);
            event.setActorUserId(actorId);
            event.setAction(action);
            event.setTargetType(targetType);
            event.setTargetId(targetId);
            event.setMetadata(Map.of("test", "true"));
            event.setCreatedAt(createdAt);
            auditEventRepository.saveAndFlush(event);
        }

        @Test
        @DisplayName("Audit events are returned in descending order by created_at")
        void auditEvents_orderedDescending() throws Exception {
            var result = mockMvc.perform(get("/api/audit-events")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            // Parse the timestamps and verify descending order
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            var tree = mapper.readTree(body);
            var content = tree.get("content");

            for (int i = 1; i < content.size(); i++) {
                String prev = content.get(i - 1).get("createdAt").asText();
                String curr = content.get(i).get("createdAt").asText();
                Instant prevInstant = Instant.parse(prev);
                Instant currInstant = Instant.parse(curr);
                assertThat(currInstant).isBeforeOrEqualTo(prevInstant);
            }
        }

        @Test
        @DisplayName("Filter by action with AND logic")
        void filterByAction_andLogic() throws Exception {
            var result = mockMvc.perform(get("/api/audit-events")
                            .param("action", "team.created")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            var tree = mapper.readTree(result.getResponse().getContentAsString());
            var content = tree.get("content");

            // All returned events must have action "team.created"
            for (var node : content) {
                assertThat(node.get("action").asText()).isEqualTo("team.created");
            }
        }

        @Test
        @DisplayName("Filter by target_type with AND logic")
        void filterByTargetType_andLogic() throws Exception {
            var result = mockMvc.perform(get("/api/audit-events")
                            .param("target_type", "team_member")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            var tree = mapper.readTree(result.getResponse().getContentAsString());
            var content = tree.get("content");

            // All returned events must have targetType "team_member"
            for (var node : content) {
                assertThat(node.get("targetType").asText()).isEqualTo("team_member");
            }
        }

        @Test
        @DisplayName("Multiple filters combine with AND logic (action + actor)")
        void multipleFilters_andLogic() throws Exception {
            var result = mockMvc.perform(get("/api/audit-events")
                            .param("action", "team.created")
                            .param("actor_user_id", auditActorId.toString())
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            var tree = mapper.readTree(result.getResponse().getContentAsString());
            var content = tree.get("content");

            // All returned events must satisfy BOTH filters
            for (var node : content) {
                assertThat(node.get("action").asText()).isEqualTo("team.created");
                assertThat(node.get("actorUserId").asText()).isEqualTo(auditActorId.toString());
            }
            // We know there are exactly 2 events matching this combination
            assertThat(content.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("Adding more filters never increases result set")
        void addingFilters_neverIncreasesResults() throws Exception {
            // No filters - get baseline count
            var baseResult = mockMvc.perform(get("/api/audit-events")
                            .param("size", "100")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            long baseCount = mapper.readTree(baseResult.getResponse().getContentAsString())
                    .get("totalElements").asLong();

            // One filter
            var filteredResult = mockMvc.perform(get("/api/audit-events")
                            .param("size", "100")
                            .param("target_type", "team")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            long filteredCount = mapper.readTree(filteredResult.getResponse().getContentAsString())
                    .get("totalElements").asLong();

            assertThat(filteredCount).isLessThanOrEqualTo(baseCount);

            // Two filters
            var moreFiltered = mockMvc.perform(get("/api/audit-events")
                            .param("size", "100")
                            .param("target_type", "team")
                            .param("action", "team.created")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andReturn();

            long moreFilteredCount = mapper.readTree(moreFiltered.getResponse().getContentAsString())
                    .get("totalElements").asLong();

            assertThat(moreFilteredCount).isLessThanOrEqualTo(filteredCount);
        }
    }

    // ========================================================================
    // Property 12: /api/me returns correct user profile
    // ========================================================================

    @Nested
    @DisplayName("Property 12: /api/me returns correct user profile")
    class ApiMeReturnsCorrectProfile {

        @Test
        @DisplayName("/api/me returns id, email, role, and organizationId matching database")
        void apiMe_returnsCorrectProfile() throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(adminUserId.toString()))
                    .andExpect(jsonPath("$.email").value("admin@teamtest.com"))
                    .andExpect(jsonPath("$.displayName").value("Admin User"))
                    .andExpect(jsonPath("$.role").value("Org_Admin"))
                    .andExpect(jsonPath("$.organizationId").value(orgId.toString()));
        }

        @Test
        @DisplayName("/api/me returns correct profile for different roles")
        void apiMe_correctForSkillAuthor() throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(Role.Skill_Author, orgId, memberUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(memberUserId.toString()))
                    .andExpect(jsonPath("$.email").value("member@teamtest.com"))
                    .andExpect(jsonPath("$.displayName").value("Member User"))
                    .andExpect(jsonPath("$.role").value("Skill_Author"))
                    .andExpect(jsonPath("$.organizationId").value(orgId.toString()));
        }

        @Test
        @DisplayName("/api/me returns correct profile for Skill_Consumer")
        void apiMe_correctForSkillConsumer() throws Exception {
            mockMvc.perform(get("/api/me")
                            .with(authenticatedAs(Role.Skill_Consumer, orgId, skillConsumerUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(skillConsumerUserId.toString()))
                    .andExpect(jsonPath("$.email").value("consumer@teamtest.com"))
                    .andExpect(jsonPath("$.displayName").value("Consumer User"))
                    .andExpect(jsonPath("$.role").value("Skill_Consumer"))
                    .andExpect(jsonPath("$.organizationId").value(orgId.toString()));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Creates a RequestPostProcessor that sets up the SecurityContext with an
     * AlexandriaAuthentication for the given role, organization, and user ID.
     * Bypasses the UserProvisioningFilter.
     */
    private static RequestPostProcessor authenticatedAs(Role role, UUID organizationId, UUID userId) {
        return (MockHttpServletRequest request) -> {
            var userDetails = new AlexandriaUserDetails(userId, organizationId, role,
                    role.name().toLowerCase() + "@test.com");

            Jwt jwt = Jwt.withTokenValue("mock-token")
                    .header("alg", "RS256")
                    .claim("sub", "subject-" + role.name().toLowerCase())
                    .claim("email", role.name().toLowerCase() + "@test.com")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            var jwtAuth = new JwtAuthenticationToken(jwt);
            var alexandriaAuth = new UserProvisioningFilter.AlexandriaAuthentication(jwtAuth, userDetails);

            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(alexandriaAuth);

            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .securityContext(securityContext).postProcessRequest(request);

            return request;
        };
    }
}
