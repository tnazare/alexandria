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
import org.junit.jupiter.api.Tag;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tagged (slow) smoke test for resolver latency and graceful FTS-only operation
 * (skill-resolver Requirements 14.1, 14.2, 14.4). Seeds a modest catalog and asserts resolve and
 * fetch complete well within budget over the existing full-text indexes, with no semantic-search
 * capability configured (the resolver runs FTS-only and does not error).
 *
 * <p>Tagged {@code slow} so it can be excluded from fast runs; it still runs in a normal verify.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("slow")
@Import(ResolverLatencySmokeTest.JwtDecoderTestConfig.class)
@DisplayName("Resolver Latency Smoke Test")
class ResolverLatencySmokeTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private ServiceTokenRepository serviceTokenRepository;

    private static final String TOKEN = "alx_latency_token_secret";
    private static final int SEED_COUNT = 200;
    private UUID sampleVersionId;

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        OrganizationEntity org = org("lat-org");
        UUID orgId = org.getId();
        UUID user = user(orgId).getId();
        TeamEntity team = team(orgId, user);

        for (int i = 0; i < SEED_COUNT; i++) {
            UUID vid = skill(orgId, team.getId(), "lat-skill-" + i, "Latency Skill " + i,
                    "latencytoken workflow number " + i + " for integration testing", user);
            if (i == 0) {
                sampleVersionId = vid;
            }
        }
        serviceToken(orgId, TOKEN);
    }

    @Test
    @DisplayName("resolve completes within budget over FTS on a seeded catalog")
    void resolveWithinBudget() throws Exception {
        // Warm once, then measure.
        resolve();
        long start = System.nanoTime();
        resolve();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Generous CI-friendly bound; the requirement targets p95 < 500ms server-side.
        assertThat(elapsedMs).as("resolve latency ms").isLessThan(2000);
    }

    @Test
    @DisplayName("fetch completes within budget")
    void fetchWithinBudget() throws Exception {
        long start = System.nanoTime();
        mockMvc.perform(get("/api/agent/v1/skills/" + sampleVersionId + "/instructions")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("fetch latency ms").isLessThan(2000);
    }

    private void resolve() throws Exception {
        mockMvc.perform(post("/api/agent/v1/skills/resolve")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"latencytoken workflow\",\"limit\":10}"))
                .andExpect(status().isOk());
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
        u.setExternalSubject("lat-" + UUID.randomUUID());
        u.setEmail("lat@svc.test");
        u.setRole("Skill_Consumer");
        u.setEnabled(true);
        return userRepository.saveAndFlush(u);
    }

    private TeamEntity team(UUID orgId, UUID createdBy) {
        var t = new TeamEntity();
        t.setOrganizationId(orgId);
        t.setName("Lat Team");
        t.setSlug("lat-team-" + UUID.randomUUID());
        t.setCreatedBy(createdBy);
        return teamRepository.saveAndFlush(t);
    }

    private UUID skill(UUID orgId, UUID teamId, String slug, String name, String instructions,
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
        skill = skillRepository.saveAndFlush(skill);
        skillRepository.rebuildSearchVector(skill.getId(), "", instructions);
        return v.getId();
    }

    private void serviceToken(UUID orgId, String rawSecret) {
        var token = new ServiceTokenEntity();
        token.setOrganizationId(orgId);
        token.setName("lat");
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
                throw new org.springframework.security.oauth2.jwt.JwtException("Not used in latency test");
            };
        }
    }
}
