package com.alexandria.agent.hook;

import com.alexandria.agent.ResolverService;
import com.alexandria.agent.dto.Capabilities;
import com.alexandria.agent.dto.Delivery;
import com.alexandria.agent.dto.Governance;
import com.alexandria.agent.dto.Resolution;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.dto.ResolveResult;
import com.alexandria.agent.dto.SkillRef;
import com.alexandria.agent.render.FetchResult;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverProperties;
import com.alexandria.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HookService} (skill-resolver Property 12, Requirement 11): fail-open,
 * size cap, session cache, relevance gate, and injection shape.
 */
@Tag("skill-resolver")
class HookServiceTest {

    private static final ResolverProperties PROPS = new ResolverProperties(
            "https://alx.example.com", 25, 5, "floating_latest_approved",
            64, 12, true); // 64-byte cap, 12-char min prompt, inject on prompt

    private ResolverService resolver;
    private HookService hookService;
    private final AlexandriaUserDetails identity =
            new AlexandriaUserDetails(UUID.randomUUID(), UUID.randomUUID(), Role.Skill_Consumer, "a@t");

    @BeforeEach
    void setUp() {
        resolver = mock(ResolverService.class);
        hookService = new HookService(resolver, PROPS);
    }

    private HookInvocation promptEvent(String sessionId, String prompt) {
        return new HookInvocation(HookInvocation.HARNESS_CLAUDE, HookInvocation.EVENT_USER_PROMPT,
                sessionId, prompt, null);
    }

    private ResolveResponse oneResult(UUID versionId) {
        ResolveResult r = new ResolveResult("skill",
                new SkillRef(UUID.randomUUID(), versionId, "https://alx.example.com"),
                "Skill", "summary", 0.9,
                new Governance("Company_Approved", "low", "organization", "Team", false, null),
                List.of("Matched full-text search"),
                new Resolution("pinned", "1", true, false, "hint"),
                new Delivery("/fetch", List.of("agent_skill")),
                null, List.of());
        return new ResolveResponse("q", "1.0", new Capabilities(true, true, false, false), List.of(r));
    }

    @Test
    void injectsResolvedSkillOnRelevantPrompt() {
        UUID versionId = UUID.randomUUID();
        when(resolver.resolve(any(), eq(identity))).thenReturn(oneResult(versionId));
        when(resolver.fetch(eq(versionId), any(), eq("agent_skill"), eq("hook"), eq(identity)))
                .thenReturn(new FetchResult("agent_skill", "text/markdown", "SHORT"));

        JsonNode result = hookService.handle(promptEvent("s1", "review this auth change carefully"), identity);

        assertThat(result.has("additionalContext")).isTrue();
        assertThat(result.get("additionalContext").asText("")).isEqualTo("SHORT");
    }

    @Test
    void relevanceGateSkipsTrivialPrompt() {
        JsonNode result = hookService.handle(promptEvent("s1", "hi"), identity);

        assertThat(result.isEmpty()).isTrue();
        verify(resolver, times(0)).resolve(any(), any());
    }

    @Test
    void failsOpenWhenResolveThrows() {
        when(resolver.resolve(any(), eq(identity))).thenThrow(new RuntimeException("boom"));

        JsonNode result = hookService.handle(promptEvent("s1", "generate integration tests please"), identity);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void emptyWhenNoResults() {
        when(resolver.resolve(any(), eq(identity)))
                .thenReturn(new ResolveResponse("q", "1.0",
                        new Capabilities(true, true, false, false), List.of()));

        JsonNode result = hookService.handle(promptEvent("s1", "some substantial task prompt"), identity);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void injectionIsSizeCapped() {
        UUID versionId = UUID.randomUUID();
        String big = "x".repeat(500); // exceeds the 64-byte cap
        when(resolver.resolve(any(), eq(identity))).thenReturn(oneResult(versionId));
        when(resolver.fetch(eq(versionId), any(), any(), any(), eq(identity)))
                .thenReturn(new FetchResult("agent_skill", "text/markdown", big));

        JsonNode result = hookService.handle(promptEvent("s1", "a sufficiently long task prompt"), identity);

        String injected = result.get("additionalContext").asText("");
        assertThat(injected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
    }

    @Test
    void sessionCachePreventsDuplicateResolves() {
        UUID versionId = UUID.randomUUID();
        when(resolver.resolve(any(), eq(identity))).thenReturn(oneResult(versionId));
        when(resolver.fetch(eq(versionId), any(), any(), any(), eq(identity)))
                .thenReturn(new FetchResult("agent_skill", "text/markdown", "CACHED"));

        String prompt = "the same substantial prompt text";
        hookService.handle(promptEvent("sess", prompt), identity);
        hookService.handle(promptEvent("sess", prompt), identity);

        // Resolve+fetch happen only once for the identical (session, prompt).
        verify(resolver, times(1)).resolve(any(), eq(identity));
        verify(resolver, times(1)).fetch(eq(versionId), any(), any(), any(), eq(identity));
    }

    @Test
    void injectionDisabledWhenConfiguredOff() {
        ResolverProperties off = new ResolverProperties(
                "https://alx.example.com", 25, 5, "floating_latest_approved", 8192, 12, false);
        HookService disabled = new HookService(resolver, off);

        JsonNode result = disabled.handle(promptEvent("s1", "a sufficiently long task prompt"), identity);

        assertThat(result.isEmpty()).isTrue();
        verify(resolver, times(0)).resolve(any(), any());
    }

    @Test
    void preToolUseDefaultsToNoDecision() {
        JsonNode result = hookService.handle(
                new HookInvocation(HookInvocation.HARNESS_COPILOT, HookInvocation.EVENT_PRE_TOOL_USE,
                        "s1", null, "bash"), identity);

        assertThat(result.isEmpty()).isTrue();
    }
}
