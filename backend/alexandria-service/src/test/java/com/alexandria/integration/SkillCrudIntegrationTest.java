package com.alexandria.integration;

import com.alexandria.audit.AuditEventRepository;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration tests covering the full skill lifecycle via HTTP.
 *
 * <p>Exercises skill creation, retrieval, in-place draft editing, publish
 * validation (incomplete → 422, complete → 200), visibility change, deletion
 * rules, variant CRUD, and taxonomy admin authorization. Backed by a real
 * PostgreSQL instance via Testcontainers (see {@code application-test.yml}).</p>
 *
 * <p><b>Requirements: 1.1, 1.6, 2.1, 3.7, 4.5, 5.6, 6.11, 7.11, 8.4</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SkillCrudIntegrationTest.JwtDecoderTestConfig.class)
class SkillCrudIntegrationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;
    @Autowired
    private TaxonomyEntryRepository taxonomyEntryRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private UUID orgId;
    private UUID authorUserId;      // Skill_Author, member of the team
    private UUID outsiderUserId;    // Skill_Author, not a team member
    private UUID adminUserId;       // Org_Admin
    private UUID catalogAdminUserId; // Catalog_Admin
    private UUID teamId;
    private UUID taskTypeTagId;

    @BeforeAll
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        var org = new OrganizationEntity();
        org.setName("Skill CRUD Org");
        org.setSlug("skill-crud-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        orgId = org.getId();

        authorUserId = createUser("author@skilltest.com", Role.Skill_Author);
        outsiderUserId = createUser("outsider@skilltest.com", Role.Skill_Author);
        adminUserId = createUser("admin@skilltest.com", Role.Org_Admin);
        catalogAdminUserId = createUser("catalog@skilltest.com", Role.Catalog_Admin);

        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName("Skill Team");
        team.setSlug("skill-team-" + UUID.randomUUID().toString().substring(0, 8));
        team.setDescription("Owns skills");
        team.setCreatedBy(authorUserId);
        team = teamRepository.saveAndFlush(team);
        teamId = team.getId();

        var member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(authorUserId);
        member.setTeamRole("owner");
        teamMemberRepository.saveAndFlush(member);

        // Seed a task_type taxonomy entry used for publishing.
        var tag = new TaxonomyEntryEntity();
        tag.setOrganizationId(orgId);
        tag.setTagType("task_type");
        tag.setValue("refactoring");
        tag.setActive(true);
        tag = taxonomyEntryRepository.saveAndFlush(tag);
        taskTypeTagId = tag.getId();
    }

    private UUID createUser(String email, Role role) {
        var user = new UserEntity();
        user.setOrganizationId(orgId);
        user.setExternalSubject("subject-" + email);
        user.setEmail(email);
        user.setDisplayName(email);
        user.setRole(role.name());
        user.setEnabled(true);
        return userRepository.saveAndFlush(user).getId();
    }

    @Nested
    @DisplayName("Full skill lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName("create -> get -> edit -> publish (422 then 200) -> visibility -> delete rules")
        void fullLifecycle() throws Exception {
            // 1. Create skill as author (team member) -> 201, Draft, private, version 1
            var createResult = mockMvc.perform(post("/api/skills")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Extract Method Helper",
                                     "description":"Guides safe method extraction",
                                     "ownerTeamId":"%s",
                                     "canonicalInstructions":"Do the refactoring carefully."}""".formatted(teamId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lifecycleStatus").value("Draft"))
                    .andExpect(jsonPath("$.visibility").value("private"))
                    .andExpect(jsonPath("$.currentVersionNumber").value(1))
                    .andExpect(jsonPath("$.slug").value("extract-method-helper"))
                    .andReturn();

            UUID skillId = UUID.fromString(node(createResult).get("id").asText());

            // 2. GET the skill -> 200 with matching fields
            mockMvc.perform(get("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Extract Method Helper"))
                    .andExpect(jsonPath("$.ownerTeamName").value("Skill Team"));

            // 3. Edit draft in-place (no changelog required, version stays 1)
            mockMvc.perform(put("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"applicability":"Java projects",
                                     "riskLevel":"low",
                                     "supportedTools":["cursor","aws_kiro"],
                                     "tagIds":["%s"]}""".formatted(taskTypeTagId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentVersionNumber").value(1))
                    .andExpect(jsonPath("$.riskLevel").value("low"));

            // 4a. Publish while missing examples -> 422 listing missing field
            mockMvc.perform(post("/api/skills/{id}/publish", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.missingFields").isArray())
                    .andExpect(jsonPath("$.missingFields[?(@ == 'examples')]").exists());

            // 4b. Add the missing example, then publish -> 200 Published
            mockMvc.perform(put("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"examples":"Before/after code sample"}"""))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/skills/{id}/publish", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lifecycleStatus").value("Published"));

            // 5. Change visibility to organization -> 200
            mockMvc.perform(patch("/api/skills/{id}/visibility", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"visibility":"organization"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visibility").value("organization"));

            // 6. Delete a published skill -> 409 (only Draft can be deleted)
            mockMvc.perform(delete("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isConflict());

            // 7. Audit events recorded for this skill (created, published at minimum)
            long skillAuditEvents = auditEventRepository.findAll().stream()
                    .filter(e -> skillId.equals(e.getTargetId()))
                    .count();
            assertThat(skillAuditEvents).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("outsider (non-member, non-admin) cannot edit -> 403")
        void outsiderCannotEdit() throws Exception {
            var createResult = mockMvc.perform(post("/api/skills")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Guarded Skill",
                                     "description":"desc",
                                     "ownerTeamId":"%s",
                                     "canonicalInstructions":"instructions"}""".formatted(teamId)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID skillId = UUID.fromString(node(createResult).get("id").asText());

            mockMvc.perform(put("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, outsiderUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"description":"hijacked"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("draft skill can be deleted -> 204")
        void draftCanBeDeleted() throws Exception {
            var createResult = mockMvc.perform(post("/api/skills")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Disposable Draft",
                                     "description":"desc",
                                     "ownerTeamId":"%s",
                                     "canonicalInstructions":"instructions"}""".formatted(teamId)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID skillId = UUID.fromString(node(createResult).get("id").asText());

            mockMvc.perform(delete("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/skills/{id}", skillId)
                            .with(authenticatedAs(Role.Org_Admin, orgId, adminUserId)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Variant CRUD lifecycle")
    class VariantLifecycle {

        @Test
        @DisplayName("create -> update -> delete variant on version 1")
        void variantCrud() throws Exception {
            var createResult = mockMvc.perform(post("/api/skills")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Variant Host Skill",
                                     "description":"desc",
                                     "ownerTeamId":"%s",
                                     "canonicalInstructions":"canonical instructions"}""".formatted(teamId)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID skillId = UUID.fromString(node(createResult).get("id").asText());

            // Create variant for cursor
            mockMvc.perform(post("/api/skills/{id}/versions/{v}/variants", skillId, 1)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"tool":"cursor","instructions":"cursor-specific instructions"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tool").value("cursor"));

            // Duplicate variant same tool -> 409
            mockMvc.perform(post("/api/skills/{id}/versions/{v}/variants", skillId, 1)
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"tool":"cursor","instructions":"dup"}"""))
                    .andExpect(status().isConflict());

            // Update variant
            mockMvc.perform(put("/api/skills/{id}/versions/{v}/variants/{tool}", skillId, 1, "cursor")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"tool":"cursor","instructions":"updated cursor instructions"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instructions").value("updated cursor instructions"));

            // Delete variant
            mockMvc.perform(delete("/api/skills/{id}/versions/{v}/variants/{tool}", skillId, 1, "cursor")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isNoContent());

            // Delete again -> 404
            mockMvc.perform(delete("/api/skills/{id}/versions/{v}/variants/{tool}", skillId, 1, "cursor")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Taxonomy authorization")
    class TaxonomyAuthorization {

        @Test
        @DisplayName("Catalog_Admin can create taxonomy entry -> 201")
        void catalogAdminCanCreate() throws Exception {
            mockMvc.perform(post("/api/taxonomy/{tagType}", "language")
                            .with(authenticatedAs(Role.Catalog_Admin, orgId, catalogAdminUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"value":"Java","description":"Java language"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.value").value("Java"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("Skill_Author cannot create taxonomy entry -> 403")
        void nonAdminCannotCreate() throws Exception {
            mockMvc.perform(post("/api/taxonomy/{tagType}", "language")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"value":"Python","description":"Python language"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Any authenticated user can list taxonomy grouped -> 200")
        void anyUserCanListGrouped() throws Exception {
            mockMvc.perform(get("/api/taxonomy")
                            .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    // --- helpers ---

    private JsonNode node(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static RequestPostProcessor authenticatedAs(Role role, UUID organizationId, UUID userId) {
        return (MockHttpServletRequest request) -> {
            var userDetails = new AlexandriaUserDetails(userId, organizationId, role,
                    role.name().toLowerCase() + "@test.com");
            var auth = new AlexandriaAuthentication(userDetails);
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(auth);
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .securityContext(securityContext).postProcessRequest(request);
            return request;
        };
    }

    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        public JwtDecoder jwtDecoder() {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.JwtException("Invalid token");
            };
        }
    }
}
