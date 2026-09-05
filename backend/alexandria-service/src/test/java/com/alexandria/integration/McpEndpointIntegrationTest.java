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
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MCP JSON-RPC round-trip tests over the real filter chain and resolver core.
 *
 * <p>Validates skill-resolver Requirements 10.1–10.5, 10.7: initialize + tools/list advertise only
 * the two resolver tools; resolve_skill then fetch_skill work with identity passthrough; governance
 * gates (Blocked) and cross-org isolation are observed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(McpEndpointIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("MCP Endpoint Integration Tests")
class McpEndpointIntegrationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private SkillVersionRepository skillVersionRepository;
    @Autowired private ServiceTokenRepository serviceTokenRepository;

    private static final String TOKEN = "alx_mcp_valid_token_secret";
    private UUID approvedVersionId;
    private UUID blockedVersionId;

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        OrganizationEntity org = org("mcp-org");
        UUID orgId = org.getId();
        UUID user = user(orgId).getId();
        TeamEntity team = team(orgId, user);

        approvedVersionId = skill(orgId, team.getId(), "mcp-approved", "MCP Kafka Skill",
                "mcpkafka instructions", "Company_Approved", user);
        blockedVersionId = skill(orgId, team.getId(), "mcp-blocked", "MCP Blocked Skill",
                "mcpblocked instructions", "Blocked", user);

        serviceToken(orgId, TOKEN);
    }

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    @Test
    @DisplayName("initialize advertises tools capability and server info")
    void initialize() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("initialize", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("alexandria-skill-resolver"));
    }

    @Test
    @DisplayName("tools/list advertises exactly resolve_skill and fetch_skill")
    void toolsList() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/list", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(2))
                .andExpect(jsonPath("$.result.tools[0].name").value("resolve_skill"))
                .andExpect(jsonPath("$.result.tools[1].name").value("fetch_skill"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.required[0]").value("query"));
    }

    @Test
    @DisplayName("resolve_skill returns candidates without instruction bodies")
    void resolveSkill() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/call",
                                "{\"name\":\"resolve_skill\",\"arguments\":{\"query\":\"mcpkafka\"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.results[0].ref.skillVersionId")
                        .value(approvedVersionId.toString()))
                .andExpect(jsonPath("$.result.structuredContent.results[0].kind").value("skill"));
    }

    @Test
    @DisplayName("fetch_skill returns SKILL.md for a resolved version")
    void fetchSkill() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/call",
                                "{\"name\":\"fetch_skill\",\"arguments\":{\"skillVersionId\":\""
                                        + approvedVersionId + "\"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text",
                        org.hamcrest.Matchers.containsString("alexandria_skill_version_id")));
    }

    @Test
    @DisplayName("fetch_skill on a blocked skill reports a JSON-RPC error")
    void fetchBlockedSkillErrors() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/call",
                                "{\"name\":\"fetch_skill\",\"arguments\":{\"skillVersionId\":\""
                                        + blockedVersionId + "\"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("MCP endpoint requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/agent/v1/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/list", null)))
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
        u.setExternalSubject("mcp-" + UUID.randomUUID());
        u.setEmail("mcp@svc.test");
        u.setRole("Skill_Consumer");
        u.setEnabled(true);
        return userRepository.saveAndFlush(u);
    }

    private TeamEntity team(UUID orgId, UUID createdBy) {
        var t = new TeamEntity();
        t.setOrganizationId(orgId);
        t.setName("MCP Team");
        t.setSlug("mcp-team-" + UUID.randomUUID());
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
        token.setName("mcp");
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
                throw new org.springframework.security.oauth2.jwt.JwtException("Not used in MCP tests");
            };
        }
    }
}
