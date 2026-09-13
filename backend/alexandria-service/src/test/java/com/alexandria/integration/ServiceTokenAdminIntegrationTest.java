package com.alexandria.integration;

import com.alexandria.agent.ServiceTokenRepository;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the service-token admin API over the real filter chain (mcp-client-readiness
 * Requirements 6.1-6.6, 7.1-7.6, 8.4, 9.1).
 *
 * <p>Covers the full mint -> authenticate -> revoke lifecycle: an Org_Admin mints a token, the raw
 * secret authenticates against the agent resolve endpoint (proving hashing parity with
 * {@code ServiceTokenAuthFilter}), listing exposes only non-secret metadata, revocation is
 * idempotent and immediately deauthenticates, cross-org access is a 404, and non-admins are 403.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ServiceTokenAdminIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("Service Token Admin Integration Tests")
class ServiceTokenAdminIntegrationTest {

    private static final String PATH = "/api/agent/v1/service-tokens";

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private ServiceTokenRepository serviceTokenRepository;

    private UUID orgAId;
    private UUID adminAId;
    private UUID consumerAId;
    private UUID orgBId;
    private UUID adminBId;
    private UUID orgASkillVersionId;

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        OrganizationEntity orgA = org("stk-admin-org-a");
        OrganizationEntity orgB = org("stk-admin-org-b");
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        adminAId = user(orgAId, "stk-admin-a", Role.Org_Admin).getId();
        consumerAId = user(orgAId, "stk-consumer-a", Role.Skill_Consumer).getId();
        adminBId = user(orgBId, "stk-admin-b", Role.Org_Admin).getId();

        TeamEntity teamA = team(orgAId, "STK Team A", "stk-team-a", adminAId);
        orgASkillVersionId = orgSkill(orgAId, teamA.getId(), "stk-skill-a",
                "STK Kafka Skill", "stkkafka instructions", adminAId);
    }

    // --- mint -> authenticate -> revoke lifecycle ---

    @Test
    @DisplayName("Org_Admin mints a token whose raw secret authenticates, then revoke deauthenticates")
    void mintAuthenticateRevokeLifecycle() throws Exception {
        // 1. Mint as Org_Admin -> 201, secret revealed once.
        String body = mockMvc.perform(post(PATH)
                        .with(as(adminAId, orgAId, Role.Org_Admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-runner\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.scope").value("agent_read"))
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode minted = objectMapper.readTree(body);
        String secret = minted.get("token").asText();
        UUID tokenId = UUID.fromString(minted.get("id").asText());
        org.assertj.core.api.Assertions.assertThat(secret).startsWith("alx_");

        // 2. The raw secret authenticates against the agent resolve endpoint (hashing parity).
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"stkkafka\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ref.skillVersionId").value(orgASkillVersionId.toString()));

        // 3. List shows the token by prefix, never the secret or hash.
        mockMvc.perform(get(PATH).with(as(adminAId, orgAId, Role.Org_Admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + tokenId + "')].tokenPrefix").exists())
                .andExpect(jsonPath("$[?(@.id=='" + tokenId + "')].token").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + tokenId + "')].tokenHash").isEmpty());

        // 4. Revoke -> 204.
        mockMvc.perform(delete(PATH + "/" + tokenId).with(as(adminAId, orgAId, Role.Org_Admin)))
                .andExpect(status().isNoContent());

        // 5. The same secret is now rejected.
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"stkkafka\"}"))
                .andExpect(status().isUnauthorized());

        // 6. Revoking again is idempotent -> 204.
        mockMvc.perform(delete(PATH + "/" + tokenId).with(as(adminAId, orgAId, Role.Org_Admin)))
                .andExpect(status().isNoContent());
    }

    // --- not found / cross-org ---

    @Test
    @DisplayName("revoking an unknown token id yields 404")
    void revokeUnknownYields404() throws Exception {
        mockMvc.perform(delete(PATH + "/" + UUID.randomUUID())
                        .with(as(adminAId, orgAId, Role.Org_Admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an org's admin cannot revoke another org's token (cross-org -> 404)")
    void crossOrgRevokeYields404() throws Exception {
        String body = mockMvc.perform(post(PATH)
                        .with(as(adminAId, orgAId, Role.Org_Admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"org-a-token\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orgAToken = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // Org B admin attempts to revoke org A's token.
        mockMvc.perform(delete(PATH + "/" + orgAToken)
                        .with(as(adminBId, orgBId, Role.Org_Admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("list is org-scoped: org B admin does not see org A tokens")
    void listIsOrgScoped() throws Exception {
        String body = mockMvc.perform(post(PATH)
                        .with(as(adminAId, orgAId, Role.Org_Admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"org-a-only\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orgAToken = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get(PATH).with(as(adminBId, orgBId, Role.Org_Admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + orgAToken + "')]").isEmpty());
    }

    // --- authorization ---

    @Test
    @DisplayName("a non-admin (Skill_Consumer) cannot create a token -> 403")
    void nonAdminCreateForbidden() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(as(consumerAId, orgAId, Role.Skill_Consumer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-admin (Skill_Consumer) cannot list tokens -> 403")
    void nonAdminListForbidden() throws Exception {
        mockMvc.perform(get(PATH).with(as(consumerAId, orgAId, Role.Skill_Consumer)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-admin (Skill_Consumer) cannot revoke a token -> 403")
    void nonAdminRevokeForbidden() throws Exception {
        mockMvc.perform(delete(PATH + "/" + UUID.randomUUID())
                        .with(as(consumerAId, orgAId, Role.Skill_Consumer)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("missing validation (blank name) yields 400")
    void blankNameYields400() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(as(adminAId, orgAId, Role.Org_Admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- auth post-processor: inject an AlexandriaAuthentication, bypassing the JWT decoder ---

    private RequestPostProcessor as(UUID userId, UUID orgId, Role role) {
        return request -> {
            var userDetails = new AlexandriaUserDetails(userId, orgId, role,
                    role.name().toLowerCase() + "@stk.test");
            var auth = new AlexandriaAuthentication(userDetails);
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return SecurityMockMvcRequestPostProcessors.securityContext(ctx).postProcessRequest(request);
        };
    }

    // --- seeding helpers ---

    private OrganizationEntity org(String slugBase) {
        var o = new OrganizationEntity();
        o.setName(slugBase);
        o.setSlug(slugBase + "-" + UUID.randomUUID());
        return organizationRepository.saveAndFlush(o);
    }

    private UserEntity user(UUID orgId, String subjectBase, Role role) {
        var u = new UserEntity();
        u.setOrganizationId(orgId);
        u.setExternalSubject(subjectBase + "-" + UUID.randomUUID());
        u.setEmail(subjectBase + "@stk.test");
        u.setRole(role.name());
        u.setEnabled(true);
        return userRepository.saveAndFlush(u);
    }

    private TeamEntity team(UUID orgId, String name, String slug, UUID createdBy) {
        var t = new TeamEntity();
        t.setOrganizationId(orgId);
        t.setName(name);
        t.setSlug(slug + "-" + UUID.randomUUID());
        t.setCreatedBy(createdBy);
        return teamRepository.saveAndFlush(t);
    }

    private UUID orgSkill(UUID orgId, UUID teamId, String slug, String name, String instructions,
                          UUID createdBy) {
        var skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName(name);
        skill.setSlug(slug + "-" + UUID.randomUUID());
        skill.setDescription("desc");
        skill.setVisibility("organization");
        skill.setLifecycleStatus("Company_Approved");
        skill.setCreatedBy(createdBy);
        skill = skillRepository.saveAndFlush(skill);

        var v = new SkillVersionEntity();
        v.setSkillId(skill.getId());
        v.setVersionNumber(1);
        v.setCanonicalInstructions(instructions);
        v.setPublishedAt(Instant.now());
        v = skillVersionRepository.saveAndFlush(v);

        skill.setCurrentVersionId(v.getId());
        skillRepository.saveAndFlush(skill);
        skillRepository.rebuildSearchVector(skill.getId(), "", instructions);
        return v.getId();
    }

    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @Primary
        public JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("Not used in service-token admin tests");
            };
        }
    }
}
