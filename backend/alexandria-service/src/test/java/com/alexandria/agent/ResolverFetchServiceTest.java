package com.alexandria.agent;

import com.alexandria.agent.dto.NativeSkillResponse;
import com.alexandria.agent.render.AgentSkillRenderer;
import com.alexandria.agent.render.FetchResult;
import com.alexandria.agent.render.NativeRenderer;
import com.alexandria.agent.render.SkillFetchRendererRegistry;
import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverProperties;
import com.alexandria.skill.SkillNotFoundException;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.skill.variant.SkillVariantService;
import com.alexandria.team.TeamRepository;
import com.alexandria.usage.UsageEventService;
import com.alexandria.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavior tests for {@link ResolverServiceImpl#fetch} with mocked collaborators, exercising the
 * real renderers (rendering is pure).
 *
 * <p>Validates skill-resolver:
 * <ul>
 *   <li>Property 4 — gate ladder: not-visible→404, Blocked→403, Deprecated→200+warning, OK→200.</li>
 *   <li>Property 5 — variant selected when present, canonical fallback otherwise.</li>
 *   <li>Property 6 — embedded identity present in both formats.</li>
 *   <li>Property 7 — agent_skill frontmatter uses only the six portable keys.</li>
 *   <li>Requirement 4.5 — a view usage event is recorded on success.</li>
 * </ul>
 */
@Tag("skill-resolver")
class ResolverFetchServiceTest {

    private static final ResolverProperties PROPS =
            new ResolverProperties("https://alx.example.com", 25, 5, "floating_latest_approved");

    private SkillResolverRepository resolverRepository;
    private TeamRepository teamRepository;
    private SkillService skillService;
    private SkillVariantService variantService;
    private UsageEventService usageEventService;
    private AuditService auditService;
    private ResolverServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private AlexandriaUserDetails identity;

    @BeforeEach
    void setUp() {
        resolverRepository = mock(SkillResolverRepository.class);
        teamRepository = mock(TeamRepository.class);
        skillService = mock(SkillService.class);
        variantService = mock(SkillVariantService.class);
        usageEventService = mock(UsageEventService.class);
        auditService = mock(AuditService.class);
        SkillFetchRendererRegistry registry = new SkillFetchRendererRegistry(
                List.of(new AgentSkillRenderer(), new NativeRenderer()));
        service = new ResolverServiceImpl(resolverRepository, teamRepository, PROPS,
                skillService, variantService, registry, usageEventService, auditService);
        identity = new AlexandriaUserDetails(userId, orgId, Role.Skill_Consumer, "agent@test.com");
        when(teamRepository.findByIdAndOrganizationId(any(), any())).thenReturn(Optional.empty());
    }

    private SkillVersionEntity version() {
        SkillVersionEntity v = new SkillVersionEntity();
        v.setId(versionId);
        v.setSkillId(skillId);
        v.setVersionNumber(3);
        v.setCanonicalInstructions("Canonical body");
        v.setWhenToUse("When you need X");
        return v;
    }

    private SkillEntity skill(String status) {
        SkillEntity s = new SkillEntity();
        s.setId(skillId);
        s.setOrganizationId(orgId);
        s.setOwnerTeamId(UUID.randomUUID());
        s.setName("Auth Review");
        s.setSlug("auth-review");
        s.setDescription("Review auth");
        s.setLifecycleStatus(status);
        s.setRiskLevel("low");
        s.setVisibility("organization");
        s.setCurrentVersionId(versionId);
        return s;
    }

    // --- Property 4: gate ladder ---

    @Test
    void notVisibleVersionYields404() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetch(versionId, null, "agent_skill", "rest", identity))
                .isInstanceOf(SkillNotFoundException.class);
    }

    @Test
    void hiddenSkillYields404ViaVisibilityRecheck() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity))
                .thenThrow(new SkillNotFoundException("Skill not found"));

        assertThatThrownBy(() -> service.fetch(versionId, null, "agent_skill", "rest", identity))
                .isInstanceOf(SkillNotFoundException.class);
    }

    @Test
    void blockedSkillYields403AndAuditsAndRecordsNoUsage() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Blocked"));

        assertThatThrownBy(() -> service.fetch(versionId, null, "agent_skill", "rest", identity))
                .isInstanceOf(ResolverBlockedException.class);

        verify(auditService).record(eq(orgId), eq(userId), eq("skill.resolver_fetch_denied"),
                eq("skill"), eq(skillId), any());
        verify(usageEventService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deprecatedSkillReturnsWithWarning() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        SkillEntity dep = skill("Deprecated");
        dep.setDeprecationReason("Superseded by v4");
        when(skillService.getById(skillId, identity)).thenReturn(dep);

        FetchResult result = service.fetch(versionId, null, "agent_skill", "rest", identity);

        String md = (String) result.body();
        assertThat(md).contains("deprecated: \"true\"");
        assertThat(md).contains("Superseded by v4");
    }

    // --- Property 5: variant fallback ---

    @Test
    void usesVariantWhenPresent() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Published"));
        when(variantService.resolve(versionId, "cursor")).thenReturn("Cursor-specific body");

        FetchResult result = service.fetch(versionId, "cursor", "alexandria_native", "rest", identity);

        NativeSkillResponse body = (NativeSkillResponse) result.body();
        assertThat(body.variant().tool()).isEqualTo("cursor");
        assertThat(body.variant().isCanonicalFallback()).isFalse();
        assertThat(body.variant().instructions()).isEqualTo("Cursor-specific body");
    }

    @Test
    void fallsBackToCanonicalWhenNoVariant() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Published"));
        when(variantService.resolve(versionId, "cursor")).thenReturn(null);

        FetchResult result = service.fetch(versionId, "cursor", "alexandria_native", "rest", identity);

        NativeSkillResponse body = (NativeSkillResponse) result.body();
        assertThat(body.variant().isCanonicalFallback()).isTrue();
        assertThat(body.variant().instructions()).isEqualTo("Canonical body");
    }

    // --- Property 6 + 7: identity + frontmatter compliance ---

    @Test
    void agentSkillFrontmatterUsesOnlySixPortableKeysAndCarriesIdentityInMetadata() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Company_Approved"));

        FetchResult result = service.fetch(versionId, null, "agent_skill", "rest", identity);
        String md = (String) result.body();

        // Extract frontmatter between the first two --- lines.
        int first = md.indexOf("---");
        int second = md.indexOf("---", first + 3);
        String frontmatter = md.substring(first + 3, second);

        // Top-level keys (no indentation) must be a subset of the six portable spec fields.
        List<String> allowed = List.of("name", "description", "license", "compatibility",
                "allowed-tools", "metadata");
        for (String line : frontmatter.split("\n")) {
            if (line.isBlank() || line.startsWith("  ")) {
                continue; // nested metadata entries are indented
            }
            String key = line.contains(":") ? line.substring(0, line.indexOf(':')).trim() : line.trim();
            if (!key.isBlank()) {
                assertThat(allowed).as("unexpected top-level frontmatter key: %s", key).contains(key);
            }
        }

        // Identity lives inside metadata, and the export marker is present.
        assertThat(frontmatter).contains("alexandria_skill_id: \"" + skillId + "\"");
        assertThat(frontmatter).contains("alexandria_skill_version_id: \"" + versionId + "\"");
        assertThat(frontmatter).contains("source_instance: \"https://alx.example.com\"");
        assertThat(md).contains("<!-- alexandria:start skill=auth-review version=3 id=" + versionId + " -->");
        assertThat(md).contains("<!-- alexandria:end -->");
    }

    @Test
    void nativeFormatCarriesIdentityAndRecordsViewUsage() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Published"));

        FetchResult result = service.fetch(versionId, null, "alexandria_native", "rest", identity);

        NativeSkillResponse body = (NativeSkillResponse) result.body();
        assertThat(body.ref().skillId()).isEqualTo(skillId);
        assertThat(body.ref().skillVersionId()).isEqualTo(versionId);
        assertThat(body.ref().sourceInstance()).isEqualTo("https://alx.example.com");

        verify(usageEventService, times(1)).record(
                eq(orgId), eq(userId), eq(skillId), eq(versionId), eq("view"), eq("rest"), eq(null));
    }

    @Test
    void unknownFormatDefaultsToAgentSkill() {
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(version()));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Published"));

        FetchResult result = service.fetch(versionId, null, "totally_unknown", "rest", identity);

        assertThat(result.format()).isEqualTo("agent_skill");
        assertThat(result.body()).isInstanceOf(String.class);
    }

    // --- Sanitization ---

    @Test
    void scriptContentIsSanitizedInRenderedBody() {
        SkillVersionEntity v = version();
        v.setCanonicalInstructions("Do this <script>alert('x')</script> and more");
        when(resolverRepository.loadVersionForFetch(versionId, orgId)).thenReturn(Optional.of(v));
        when(skillService.getById(skillId, identity)).thenReturn(skill("Published"));

        FetchResult result = service.fetch(versionId, null, "agent_skill", "rest", identity);
        String md = (String) result.body();

        assertThat(md).doesNotContain("<script>");
        assertThat(md).doesNotContain("</script>");
    }
}
