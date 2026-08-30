package com.alexandria.integration;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.usage.UsageEventRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/skills/{id}/export} against a real PostgreSQL
 * (Testcontainers). Exercises each format round-trip, error paths, usage-event recording, and
 * visibility parity (Property 7).
 *
 * <p><b>Requirements: 3.1, 3.6, 3.7, 4.1, 5.1, 5.3</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SkillExportIntegrationTest.JwtDecoderTestConfig.class)
class SkillExportIntegrationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private UsageEventRepository usageEventRepository;

    private UUID orgId;
    private UUID adminUserId;
    private UUID consumerUserId;
    private UUID otherOrgId;
    private UUID teamId;

    @BeforeAll
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        var org = new OrganizationEntity();
        org.setName("Export Org");
        org.setSlug("export-org-" + UUID.randomUUID());
        orgId = organizationRepository.saveAndFlush(org).getId();

        var other = new OrganizationEntity();
        other.setName("Other Export Org");
        other.setSlug("other-export-org-" + UUID.randomUUID());
        otherOrgId = organizationRepository.saveAndFlush(other).getId();

        adminUserId = createUser(orgId, "admin@export.test", Role.Catalog_Admin);
        consumerUserId = createUser(orgId, "consumer@export.test", Role.Skill_Consumer);

        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName("Export Team");
        team.setSlug("export-team-" + UUID.randomUUID().toString().substring(0, 8));
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

    /** Creates a persisted skill with a current version. */
    private UUID createSkill(String status, String visibility) {
        var skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Export Skill " + UUID.randomUUID().toString().substring(0, 8));
        skill.setSlug("export-skill-" + UUID.randomUUID().toString().substring(0, 8));
        skill.setDescription("A skill to export");
        skill.setLifecycleStatus(status);
        skill.setVisibility(visibility);
        skill.setRiskLevel("low");
        skill.setCreatedBy(adminUserId);
        skill = skillRepository.saveAndFlush(skill);

        var version = new SkillVersionEntity();
        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions("Do the thing carefully");
        version = skillVersionRepository.saveAndFlush(version);
        skill.setCurrentVersionId(version.getId());
        return skillRepository.saveAndFlush(skill).getId();
    }

    private long usageCount(UUID skillId, String eventType) {
        return usageEventRepository.countBySkillIdAndEventType(skillId, eventType);
    }

    @Test
    @DisplayName("each format renders 200 text/plain containing the skill content")
    void allFormatsRoundTrip() throws Exception {
        UUID skillId = createSkill("Published", "organization");
        String[] formats = {
                "generic_markdown", "github_copilot", "codex",
                "agents_md", "cursor_rule", "kiro_steering"
        };
        for (String format : formats) {
            mockMvc.perform(get("/api/skills/{id}/export", skillId)
                            .param("format", format)
                            .with(consumer()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/plain"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Do the thing carefully")));
        }
    }

    @Test
    @DisplayName("download records an export event; copy records a copy event")
    void recordsUsageEvents() throws Exception {
        UUID skillId = createSkill("Published", "organization");

        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "generic_markdown")
                        .param("source", "download")
                        .with(consumer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "generic_markdown")
                        .param("source", "copy")
                        .with(consumer()))
                .andExpect(status().isOk());

        assertThat(usageCount(skillId, "export")).isEqualTo(1);
        assertThat(usageCount(skillId, "copy")).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown format -> 400, no usage event")
    void unknownFormat() throws Exception {
        UUID skillId = createSkill("Published", "organization");
        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "nonsense")
                        .with(consumer()))
                .andExpect(status().isBadRequest());
        assertThat(usageCount(skillId, "export")).isZero();
    }

    @Test
    @DisplayName("unknown tool -> 400, no usage event")
    void unknownTool() throws Exception {
        UUID skillId = createSkill("Published", "organization");
        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "generic_markdown")
                        .param("tool", "not_a_tool")
                        .with(consumer()))
                .andExpect(status().isBadRequest());
        assertThat(usageCount(skillId, "export")).isZero();
    }

    @Test
    @DisplayName("missing skill -> 404")
    void missingSkill() throws Exception {
        mockMvc.perform(get("/api/skills/{id}/export", UUID.randomUUID())
                        .param("format", "generic_markdown")
                        .with(consumer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("missing version -> 404")
    void missingVersion() throws Exception {
        UUID skillId = createSkill("Published", "organization");
        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "generic_markdown")
                        .param("version", "999")
                        .with(consumer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("blocked skill -> 409, no usage event")
    void blockedSkill() throws Exception {
        UUID skillId = createSkill("Blocked", "organization");
        mockMvc.perform(get("/api/skills/{id}/export", skillId)
                        .param("format", "generic_markdown")
                        .with(consumer()))
                .andExpect(status().isConflict());
        assertThat(usageCount(skillId, "export")).isZero();
    }

    @Test
    @DisplayName("Property 7: hidden (private, non-member) skill -> 404; other-org user -> 404")
    void visibilityParity() throws Exception {
        // A private skill owned by teamId that the consumer is not a member of is not visible.
        UUID privateSkill = createSkill("Published", "private");
        mockMvc.perform(get("/api/skills/{id}/export", privateSkill)
                        .param("format", "generic_markdown")
                        .with(consumer()))
                .andExpect(status().isNotFound());
        assertThat(usageCount(privateSkill, "export")).isZero();

        // A user from another org cannot see an org-visible skill in this org.
        UUID orgSkill = createSkill("Published", "organization");
        UUID otherConsumer = createUser(otherOrgId, "otherconsumer@x.test", Role.Skill_Consumer);
        mockMvc.perform(get("/api/skills/{id}/export", orgSkill)
                        .param("format", "generic_markdown")
                        .with(authenticatedAs(Role.Skill_Consumer, otherOrgId, otherConsumer)))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private RequestPostProcessor consumer() {
        return authenticatedAs(Role.Skill_Consumer, orgId, consumerUserId);
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
