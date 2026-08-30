package com.alexandria.skill.export;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillBlockedException;
import com.alexandria.skill.SkillNotFoundException;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.skill.variant.SkillVariantService;
import com.alexandria.skill.SkillVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Default {@link SkillExportService}. Loads the skill through the visibility-enforcing
 * {@code SkillService}, denies blocked skills, resolves the target version and its instructions
 * (variant with canonical fallback), renders via the registry, and fires a usage event.
 */
@Service
@RequiredArgsConstructor
public class SkillExportServiceImpl implements SkillExportService {

    private static final String BLOCKED = "Blocked";

    /**
     * The supported variant tool identifiers — the same nine enforced by
     * {@code SkillVariantServiceImpl}. Kept in sync intentionally; an unknown tool is a 400
     * rejected before any DB work.
     */
    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "generic", "github_copilot", "codex", "cursor", "aws_kiro",
            "sourcegraph_cody", "claude_code", "jetbrains_ai", "internal_agent"
    );

    private final SkillService skillService;
    private final SkillVariantService variantService;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillExportRendererRegistry rendererRegistry;
    private final UsageEventService usageEventService;

    @Override
    @Transactional(readOnly = true)
    public String export(UUID skillId, ExportFormat format, Integer versionNumber,
                         String tool, ExportSource source, AlexandriaUserDetails user) {
        // 1. Validate the tool before any DB work (unknown tool -> 400).
        validateTool(tool);

        // 2. Load the skill with visibility enforcement (hidden -> 404).
        SkillEntity skill = skillService.getById(skillId, user);

        // 3. Blocked skills are non-exportable (409), regardless of format/version/tool.
        if (BLOCKED.equals(skill.getLifecycleStatus())) {
            throw new SkillBlockedException("Skill is blocked and cannot be exported");
        }

        // 4. Resolve the target version (requested number, else current).
        SkillVersionEntity version = resolveVersion(skill, versionNumber, user);

        // 5. Resolve instructions: variant for the tool, else canonical (empty is allowed).
        String instructions = null;
        if (tool != null) {
            instructions = variantService.resolve(version.getId(), tool);
        }
        if (instructions == null) {
            instructions = version.getCanonicalInstructions();
        }

        // 6. Build the render model and render.
        ExportRenderModel model = new ExportRenderModel(
                skill.getName(),
                skill.getSlug(),
                skill.getDescription(),
                instructions,
                version.getApplicability(),
                version.getWhenToUse(),
                version.getLimitations(),
                version.getExamples(),
                tool,
                skill.getRiskLevel()
        );
        String artifact = rendererRegistry.rendererFor(format).render(model);

        // 7. Record the usage event on success (fire-and-forget).
        String eventType = source == ExportSource.COPY ? "copy" : "export";
        usageEventService.record(
                user.organizationId(),
                user.userId(),
                skill.getId(),
                version.getId(),
                eventType,
                source.wireValue(),
                format.wireValue()
        );

        return artifact;
    }

    private SkillVersionEntity resolveVersion(SkillEntity skill, Integer versionNumber,
                                              AlexandriaUserDetails user) {
        if (versionNumber != null) {
            // getVersion re-enforces visibility and 404s on a missing version.
            return skillService.getVersion(skill.getId(), versionNumber, user);
        }
        UUID currentVersionId = skill.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new SkillNotFoundException("Skill has no current version");
        }
        return skillVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new SkillNotFoundException("Skill has no current version"));
    }

    private void validateTool(String tool) {
        if (tool != null && !SUPPORTED_TOOLS.contains(tool)) {
            throw new IllegalArgumentException(
                    "Unsupported tool: '" + tool + "'. Supported tools: " + SUPPORTED_TOOLS);
        }
    }
}
