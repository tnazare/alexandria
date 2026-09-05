package com.alexandria.integration;

import com.alexandria.agent.ServiceTokenRepository;
import com.alexandria.agent.entity.ServiceTokenEntity;
import com.alexandria.agent.toolsearch.ToolSearchResource;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.user.UserRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for the Claude-native tool-search backend (Requirement 12): deferred-load
 * discovery returns tool_references only for ACL-visible, non-Blocked skills, and a discovered
 * reference's version can then be fetched under the gates.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ToolSearchEndpointIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("Tool Search Endpoint Integration Tests")
class ToolSearchEndpointIntegrationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private ServiceTokenRepository serviceTokenRepository;

    private static final String TOKEN = "alx_toolsearch_token_secret";
    private UUID approvedVersionId;

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        OrganizationEntity org = org("ts-org");
        UUID orgId = org.getId();
        UUID user = user(orgId).getId();
        TeamEntity team = team(orgId, user);

        approvedVersionId = skill(orgId, team.getId(), "ts-approved", "TS Discovery Skill",
                "tsdiscovery instructions", "Company_Approved", user);
        // A blocked skill matching the same query must never be discovered.
        skill(orgId, team.getId(), "ts-blocked", "TS Blocked Skill",
                "tsdiscovery blocked", "Blocked", user);

        serviceToken(orgId, TOKEN);
    }

    @Test
    @DisplayName("discovery returns a tool_reference for the visible approved skill, not the blocked one")
    void discoversVisibleNonBlockedOnly() throws Exception {
        mockMvc.perform(post("/api/agent/v1/toolsearch")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"tsdiscovery\",\"limit\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool_references[0].type").value("tool_reference"))
                .andExpect(jsonPath("$.tool_references[0].name")
                        .value(ToolSearchResource.TOOL_NAME_PREFIX + approvedVersionId))
                // exactly one reference: the blocked skill is excluded
                .andExpect(jsonPath("$.tool_references.length()").value(1));
    }

    @Test
    @DisplayName("a discovered reference's version can be fetched")
    void discoveredReferenceIsFetchable() throws Exception {
        mockMvc.perform(get("/api/agent/v1/skills/" + approvedVersionId + "/instructions")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("format", "alexandria_native"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref.skillVersionId").value(approvedVersionId.toString()));
    }

    @Test
    @DisplayName("tool search requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/agent/v1/toolsearch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"tsdiscovery\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- seeding helpers ---

    private OrganizationEntity org(String base) {
        var o = new OrganizationEntity();
        o.setName(base);
        o.setSlug(base + "-" + UUID.randomUUID());
        return organizationRepository.saveAndFlush(o);
    }

    private UserEntity user(UUID orgId) {
        var u = new UserEntity();
        u.setOrganizationId(orgId);
        u.setExternalSubject("ts-" + UUID.randomUUID());
        u.setEmail("ts@svc.test");
        u.setRole("Skill_Consumer");
        u.setEnabled(true);
        return userRepository.saveAndFlush(u);
    }

    private TeamEntity team(UUID orgId, UUID createdBy) {
        var t = new TeamEntity();
        t.setOrganizationId(orgId);
        t.setName("TS Team");
        t.setSlug("ts-team-" + UUID.randomUUID());
        t.setCreatedBy(createdBy);
        return teamRepository.saveAndFlush(t);
    }

    private UUID skill(UUID orgId, UUID teamId, String slug, String name, String instructions,
                       String status, UUID createdBy) {
        var skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName(name);
        skill.setSlug(slug + "-" + UUID.randomUUID());
        skill.setDescription("desc");
        skill.setVisibility("organization");
        skill.setLifecycleStatus(status);
        skill.setCreatedBy(createdBy);
        skill = skillRepository.saveAndFlush(skill);

        var v = new SkillVersionEntity();
        v.setSkillId(skill.getId());
        v.setVersionNumber(1);
        v.setCanonicalInstructions(instructions);
        v.setPublishedAt(Instant.now());
        v = skillVersionRepository.saveAndFlush(v);

        skill.setCurrentVersionId(v.getId());
        skill = skillRepository.saveAndFlush(skill);
        skillRepository.rebuildSearchVector(skill.getId(), "", instructions);
        return v.getId();
    }

    private void serviceToken(UUID orgId, String rawSecret) {
        var token = new ServiceTokenEntity();
        token.setOrganizationId(orgId);
        token.setName("ts");
        token.setTokenHash(sha256(rawSecret));
        token.setTokenPrefix(rawSecret.substring(0, 12));
        token.setScope("agent_read");
        serviceTokenRepository.saveAndFlush(token);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        public JwtDecoder jwtDecoder() {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.JwtException("Not used in tool-search tests");
            };
        }
    }
}
