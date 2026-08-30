package com.alexandria.skill.export;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillBlockedException;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.skill.variant.SkillVariantService;
import com.alexandria.usage.UsageEventService;
import com.alexandria.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavior tests for {@link SkillExportServiceImpl} with mocked collaborators.
 *
 * <ul>
 *   <li><b>Property 5: Blocked denial</b> — exporting a Blocked skill throws for any
 *       format/version/tool and records no usage event.</li>
 *   <li><b>Property 6: Usage event on success only</b> — success records exactly one matching
 *       event (resolved version id + source→event_type mapping); rejection records none.</li>
 *   <li><b>Property 10: Invalid-tool rejection</b> — an unsupported tool yields 400 (before any
 *       DB work) and records no usage event.</li>
 *   <li>Variant resolution and version selection wiring.</li>
 * </ul>
 */
@Tag("skill-export")
class SkillExportServiceTest {

    private SkillService skillService;
    private SkillVariantService variantService;
    private SkillVersionRepository versionRepository;
    private UsageEventService usageEventService;
    private SkillExportServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID currentVersionId = UUID.randomUUID();
    private AlexandriaUserDetails user;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        variantService = mock(SkillVariantService.class);
        versionRepository = mock(SkillVersionRepository.class);
        usageEventService = mock(UsageEventService.class);
        service = new SkillExportServiceImpl(
                skillService, variantService, versionRepository, new registry(), usageEventService);
        user = new AlexandriaUserDetails(userId, orgId, Role.Skill_Consumer, "consumer@test.com");
    }

    /** Real registry with the real renderers (rendering is pure). */
    private static class registry extends SkillExportRendererRegistry {
        registry() {
            super(java.util.List.of(
                    new GenericMarkdownRenderer(),
                    new AgentsMdRenderer(),
                    new CursorRuleRenderer(),
                    new KiroSteeringRenderer(),
                    new GithubCopilotRenderer(),
                    new CodexRenderer()));
        }
    }

    private SkillEntity skill(String status) {
        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(UUID.randomUUID());
        skill.setName("Test Skill");
        skill.setSlug("test-skill");
        skill.setDescription("A skill");
        skill.setLifecycleStatus(status);
        skill.setRiskLevel("low");
        skill.setCurrentVersionId(currentVersionId);
        return skill;
    }

    private SkillVersionEntity version() {
        SkillVersionEntity v = new SkillVersionEntity();
        v.setId(currentVersionId);
        v.setSkillId(skillId);
        v.setVersionNumber(1);
        v.setCanonicalInstructions("Canonical instructions body");
        return v;
    }

    // --- Property 6: success records exactly one matching event ---

    @Test
    void downloadRecordsExactlyOneExportEvent() {
        when(skillService.getById(skillId, user)).thenReturn(skill("Published"));
        when(versionRepository.findById(currentVersionId)).thenReturn(Optional.of(version()));

        String artifact = service.export(skillId, ExportFormat.GENERIC_MARKDOWN, null, null,
                ExportSource.DOWNLOAD, user);

        assertThat(artifact).contains("Test Skill").contains("Canonical instructions body");
        verify(usageEventService, times(1)).record(
                eq(orgId), eq(userId), eq(skillId), eq(currentVersionId),
                eq("export"), eq("download"), eq("generic_markdown"));
    }

    @Test
    void copyRecordsExactlyOneCopyEvent() {
        when(skillService.getById(skillId, user)).thenReturn(skill("Published"));
        when(versionRepository.findById(currentVersionId)).thenReturn(Optional.of(version()));

        service.export(skillId, ExportFormat.AGENTS_MD, null, null, ExportSource.COPY, user);

        verify(usageEventService, times(1)).record(
                eq(orgId), eq(userId), eq(skillId), eq(currentVersionId),
                eq("copy"), eq("copy"), eq("agents_md"));
    }

    // --- Property 5: blocked denial, no event ---

    @Test
    void blockedSkillIsDeniedAndRecordsNoEvent() {
        when(skillService.getById(skillId, user)).thenReturn(skill("Blocked"));

        assertThatThrownBy(() -> service.export(skillId, ExportFormat.CODEX, null, null,
                ExportSource.DOWNLOAD, user))
                .isInstanceOf(SkillBlockedException.class);

        verifyNoInteractions(usageEventService);
    }

    // --- Property 10: invalid tool rejected before DB work, no event ---

    @Test
    void unsupportedToolIsRejectedBeforeAnyLoadAndRecordsNoEvent() {
        assertThatThrownBy(() -> service.export(skillId, ExportFormat.GENERIC_MARKDOWN, null,
                "not_a_tool", ExportSource.DOWNLOAD, user))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(skillService);
        verifyNoInteractions(usageEventService);
    }

    // --- Variant resolution wiring ---

    @Test
    void usesVariantInstructionsWhenAVariantExists() {
        when(skillService.getById(skillId, user)).thenReturn(skill("Published"));
        when(versionRepository.findById(currentVersionId)).thenReturn(Optional.of(version()));
        when(variantService.resolve(currentVersionId, "cursor")).thenReturn("Variant body for cursor");

        String artifact = service.export(skillId, ExportFormat.CURSOR_RULE, null, "cursor",
                ExportSource.DOWNLOAD, user);

        assertThat(artifact).contains("Variant body for cursor");
        assertThat(artifact).doesNotContain("Canonical instructions body");
    }

    @Test
    void fallsBackToCanonicalWhenNoVariantForTool() {
        when(skillService.getById(skillId, user)).thenReturn(skill("Published"));
        when(versionRepository.findById(currentVersionId)).thenReturn(Optional.of(version()));
        when(variantService.resolve(currentVersionId, "cursor")).thenReturn(null);

        String artifact = service.export(skillId, ExportFormat.CURSOR_RULE, null, "cursor",
                ExportSource.DOWNLOAD, user);

        assertThat(artifact).contains("Canonical instructions body");
    }

    // --- Version selection wiring ---

    @Test
    void versionedExportUsesRequestedVersionAndItsId() {
        UUID olderVersionId = UUID.randomUUID();
        SkillVersionEntity older = new SkillVersionEntity();
        older.setId(olderVersionId);
        older.setSkillId(skillId);
        older.setVersionNumber(2);
        older.setCanonicalInstructions("Older version body");

        when(skillService.getById(skillId, user)).thenReturn(skill("Published"));
        when(skillService.getVersion(skillId, 2, user)).thenReturn(older);

        String artifact = service.export(skillId, ExportFormat.GENERIC_MARKDOWN, 2, null,
                ExportSource.DOWNLOAD, user);

        assertThat(artifact).contains("Older version body");
        verify(usageEventService).record(
                eq(orgId), eq(userId), eq(skillId), eq(olderVersionId),
                eq("export"), eq("download"), eq("generic_markdown"));
        verify(versionRepository, never()).findById(any());
    }
}
