package com.alexandria.integration;

import com.alexandria.agent.ServiceTokenRepository;
import com.alexandria.agent.entity.ServiceTokenEntity;
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
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security tests for service-token agent authentication over the real filter chain.
 *
 * <p>Validates skill-resolver Property 11 and Requirements 9.2, 9.4, 9.5, 2.4, 4.3:
 * <ul>
 *   <li>a valid service token can resolve/fetch its own org's skills;</li>
 *   <li>a token for org A cannot see org B skills (cross-org isolation);</li>
 *   <li>revoked and expired tokens yield 401;</li>
 *   <li>an unknown/garbage token yields 401;</li>
 *   <li>fetch of a non-visible skill yields 404 (anti-enumeration).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ServiceTokenAuthIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("Service Token Auth Integration Tests")
class ServiceTokenAuthIntegrationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private ServiceTokenRepository serviceTokenRepository;

    private UUID orgAId;
    private UUID orgBId;
    private UUID orgASkillVersionId;
    private UUID orgBSkillVersionId;

    // Raw secrets (only their hashes are stored).
    private static final String ORG_A_TOKEN = "alx_orgA_valid_token_secret";
    private static final String ORG_A_REVOKED = "alx_orgA_revoked_token_secret";
    private static final String ORG_A_EXPIRED = "alx_orgA_expired_token_secret";

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        OrganizationEntity orgA = org("svc-token-org-a");
        OrganizationEntity orgB = org("svc-token-org-b");
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        UUID userA = user(orgAId, "svc-a").getId();
        UUID userB = user(orgBId, "svc-b").getId();
        TeamEntity teamA = team(orgAId, "Team A", "svc-team-a", userA);
        TeamEntity teamB = team(orgBId, "Team B", "svc-team-b", userB);

        orgASkillVersionId = orgSkill(orgAId, teamA.getId(), "svc-skill-a",
                "Org A Kafka Skill", "kafkatoken instructions A", userA);
        orgBSkillVersionId = orgSkill(orgBId, teamB.getId(), "svc-skill-b",
                "Org B Kafka Skill", "kafkatoken instructions B", userB);

        serviceToken(orgAId, "valid", ORG_A_TOKEN, null, null);
        serviceToken(orgAId, "revoked", ORG_A_REVOKED, Instant.now(), null);
        serviceToken(orgAId, "expired", ORG_A_EXPIRED, null, Instant.now().minus(1, ChronoUnit.DAYS));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("valid service token can resolve its own org's skills")
    void validTokenResolvesOwnOrg() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", bearer(ORG_A_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.resolve").value(true))
                .andExpect(jsonPath("$.results[0].ref.skillVersionId").value(orgASkillVersionId.toString()));
    }

    @Test
    @DisplayName("valid service token can fetch its own org's skill version")
    void validTokenFetchesOwnOrg() throws Exception {
        mockMvc.perform(get("/api/agent/v1/skills/" + orgASkillVersionId + "/instructions")
                        .header("Authorization", bearer(ORG_A_TOKEN))
                        .param("format", "alexandria_native"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref.skillVersionId").value(orgASkillVersionId.toString()));
    }

    @Test
    @DisplayName("token for org A cannot fetch org B skill (cross-org isolation -> 404)")
    void crossOrgFetchDenied() throws Exception {
        mockMvc.perform(get("/api/agent/v1/skills/" + orgBSkillVersionId + "/instructions")
                        .header("Authorization", bearer(ORG_A_TOKEN)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("token for org A does not surface org B skills in resolve")
    void crossOrgResolveIsolated() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", bearer(ORG_A_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\",\"limit\":25}"))
                .andExpect(status().isOk())
                // Only org A's version should appear; org B's must never be present.
                .andExpect(jsonPath("$.results[?(@.ref.skillVersionId=='" + orgBSkillVersionId + "')]").isEmpty());
    }

    @Test
    @DisplayName("revoked token yields 401")
    void revokedTokenUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", bearer(ORG_A_REVOKED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("expired token yields 401")
    void expiredTokenUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", bearer(ORG_A_EXPIRED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unknown service token yields 401")
    void unknownTokenUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", bearer("alx_this_is_not_a_real_token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no credential yields 401")
    void noCredentialUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"kafkatoken\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- seeding helpers ---

    private OrganizationEntity org(String slugBase) {
        var o = new OrganizationEntity();
        o.setName(slugBase);
        o.setSlug(slugBase + "-" + UUID.randomUUID());
        return organizationRepository.saveAndFlush(o);
    }

    private UserEntity user(UUID orgId, String subjectBase) {
        var u = new UserEntity();
        u.setOrganizationId(orgId);
        u.setExternalSubject(subjectBase + "-" + UUID.randomUUID());
        u.setEmail(subjectBase + "@svc.test");
        u.setRole("Skill_Consumer");
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

    private UUID orgSkill(UUID orgId, UUID teamId, String slug, String name,
                          String instructions, UUID createdBy) {
        var skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName(name);
        skill.setSlug(slug + "-" + UUID.randomUUID());
        skill.setDescription("desc");
        skill.setVisibility("organization");
        // Company_Approved so the default floating_latest_approved reference mode resolves it.
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
        skill = skillRepository.saveAndFlush(skill);
        skillRepository.rebuildSearchVector(skill.getId(), "", instructions);
        return v.getId();
    }

    private void serviceToken(UUID orgId, String name, String rawSecret,
                              Instant revokedAt, Instant expiresAt) {
        var token = new ServiceTokenEntity();
        token.setOrganizationId(orgId);
        token.setName(name);
        token.setTokenHash(sha256(rawSecret));
        token.setTokenPrefix(rawSecret.substring(0, Math.min(12, rawSecret.length())));
        token.setScope("agent_read");
        token.setRevokedAt(revokedAt);
        token.setExpiresAt(expiresAt);
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

    /** Mock JwtDecoder so the resource server config loads without a real OIDC provider. */
    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        public JwtDecoder jwtDecoder() {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.JwtException("Not used in service-token tests");
            };
        }
    }
}
