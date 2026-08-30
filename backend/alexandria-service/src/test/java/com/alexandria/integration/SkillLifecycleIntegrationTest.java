package com.alexandria.integration;

import com.alexandria.audit.AuditEventRepository;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the skill lifecycle governance endpoints (deprecate / block / archive /
 * restore) via HTTP, backed by a real PostgreSQL instance (Testcontainers).
 *
 * <p>Validates transition happy paths, illegal-transition 409s, non-admin 403 (Property 7),
 * missing/other-org 404, over-long reason 400, and audit recording.</p>
 *
 * <p><b>Requirements: 1.x, 2.x, 3.x, 4.x, 5.1, 5.2, 5.3, 5.4</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SkillLifecycleIntegrationTest.JwtDecoderTestConfig.class)
class SkillLifecycleIntegrationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private UUID orgId;
    private UUID adminUserId;
    private UUID authorUserId;
    private UUID otherOrgId;
    private UUID teamId;

    @BeforeAll
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        var org = new OrganizationEntity();
        org.setName("Lifecycle Org");
        org.setSlug("lifecycle-org-" + UUID.randomUUID());
        orgId = organizationRepository.saveAndFlush(org).getId();

        var other = new OrganizationEntity();
        other.setName("Other Org");
        other.setSlug("other-org-" + UUID.randomUUID());
        otherOrgId = organizationRepository.saveAndFlush(other).getId();

        adminUserId = createUser(orgId, "admin@lifecycle.test", Role.Catalog_Admin);
        authorUserId = createUser(orgId, "author@lifecycle.test", Role.Skill_Author);

        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName("Lifecycle Team");
        team.setSlug("lifecycle-team-" + UUID.randomUUID().toString().substring(0, 8));
        team.setCreatedBy(adminUserId);
        teamId = teamRepository.saveAndFlush(team).getId();
    }

    private UUID createUser(UUID organizationId, String email, Role role) {
        var u = new UserEntity();
        u.setOrganizationId(organizationId);
        u.setExternalSubject("subject-" + email);
        u.setEmail(email);
        u.setRole(role.name());
        u.setEnabled(true);
        return userRepository.saveAndFlush(u).getId();
    }

    /** Creates a persisted skill directly in the given lifecycle status. */
    private UUID createSkill(String status) {
        var skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Skill " + UUID.randomUUID().toString().substring(0, 8));
        skill.setSlug("skill-" + UUID.randomUUID().toString().substring(0, 8));
        skill.setDescription("desc");
        skill.setLifecycleStatus(status);
        skill.setVisibility("organization");
        skill.setCreatedBy(adminUserId);
        skill = skillRepository.saveAndFlush(skill);

        var version = new SkillVersionEntity();
        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions("instructions");
        version = skillVersionRepository.saveAndFlush(version);
        skill.setCurrentVersionId(version.getId());
        return skillRepository.saveAndFlush(skill).getId();
    }

    private SkillEntity reload(UUID skillId) {
        return skillRepository.findById(skillId).orElseThrow();
    }

    @Test
    @DisplayName("deprecate -> 200 Deprecated with reason; restore -> 200 back to prior")
    void deprecateThenRestore() throws Exception {
        UUID skillId = createSkill("Published");

        mockMvc.perform(post("/api/skills/{id}/deprecate", skillId)
                        .with(admin())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"outdated approach"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("Deprecated"))
                .andExpect(jsonPath("$.deprecationReason").value("outdated approach"));

        mockMvc.perform(post("/api/skills/{id}/restore", skillId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("Published"))
                .andExpect(jsonPath("$.deprecationReason").doesNotExist());

        assertThat(reload(skillId).getPriorStatus()).isNull();
    }

    @Test
    @DisplayName("block -> 200 Blocked, then restore -> 200 back to prior")
    void blockThenRestore() throws Exception {
        UUID skillId = createSkill("Published");

        mockMvc.perform(post("/api/skills/{id}/block", skillId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("Blocked"));

        mockMvc.perform(post("/api/skills/{id}/restore", skillId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("Published"));
    }

    @Test
    @DisplayName("archive -> 200 Archived")
    void archive() throws Exception {
        UUID skillId = createSkill("Draft");
        mockMvc.perform(post("/api/skills/{id}/archive", skillId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("Archived"));
    }

    @Test
    @DisplayName("illegal transition -> 409 (block an already-blocked skill; restore an active skill)")
    void illegalTransitions() throws Exception {
        UUID blocked = createSkill("Blocked");
        mockMvc.perform(post("/api/skills/{id}/block", blocked).with(admin()))
                .andExpect(status().isConflict());

        UUID active = createSkill("Published");
        mockMvc.perform(post("/api/skills/{id}/restore", active).with(admin()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Property 7: non-admin -> 403; other-org / missing -> 404")
    void authorizationAndScoping() throws Exception {
        UUID skillId = createSkill("Published");

        // Skill_Author is not a governance role.
        mockMvc.perform(post("/api/skills/{id}/deprecate", skillId)
                        .with(authenticatedAs(Role.Skill_Author, orgId, authorUserId)))
                .andExpect(status().isForbidden());
        assertThat(reload(skillId).getLifecycleStatus()).isEqualTo("Published");

        // Missing skill -> 404.
        mockMvc.perform(post("/api/skills/{id}/block", UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound());

        // Admin from another org cannot transition this org's skill -> 404.
        UUID otherAdmin = createUser(otherOrgId, "otheradmin@x.test", Role.Catalog_Admin);
        mockMvc.perform(post("/api/skills/{id}/block", skillId)
                        .with(authenticatedAs(Role.Catalog_Admin, otherOrgId, otherAdmin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deprecation reason over 1000 chars -> 400")
    void reasonTooLong() throws Exception {
        UUID skillId = createSkill("Published");
        String longReason = "x".repeat(1001);
        mockMvc.perform(post("/api/skills/{id}/deprecate", skillId)
                        .with(admin())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("transition records an audit event")
    void auditRecorded() throws Exception {
        UUID skillId = createSkill("Published");
        long before = auditEventRepository.findAll().stream()
                .filter(e -> "skill.archived".equals(e.getAction()) && skillId.equals(e.getTargetId()))
                .count();
        mockMvc.perform(post("/api/skills/{id}/archive", skillId).with(admin()))
                .andExpect(status().isOk());
        long after = auditEventRepository.findAll().stream()
                .filter(e -> "skill.archived".equals(e.getAction()) && skillId.equals(e.getTargetId()))
                .count();
        assertThat(after).isEqualTo(before + 1);
    }

    // --- helpers ---

    private RequestPostProcessor admin() {
        return authenticatedAs(Role.Catalog_Admin, orgId, adminUserId);
    }

    private static RequestPostProcessor authenticatedAs(Role role, UUID organizationId, UUID userId) {
        return (MockHttpServletRequest request) -> {
            var userDetails = new AlexandriaUserDetails(userId, organizationId, role,
                    role.name().toLowerCase() + "@test.com");
            var auth = new AlexandriaAuthentication(userDetails);
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .securityContext(ctx).postProcessRequest(request);
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
