package com.alexandria.skill.variant;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillAccessDeniedException;
import com.alexandria.skill.SkillConflictException;
import com.alexandria.skill.SkillNotFoundException;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.SkillVariantRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVariantEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of skill variant CRUD and resolution operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillVariantServiceImpl implements SkillVariantService {

    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "generic", "github_copilot", "codex", "cursor", "aws_kiro",
            "sourcegraph_cody", "claude_code", "jetbrains_ai", "internal_agent"
    );

    private final SkillVariantRepository skillVariantRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillService skillService;
    private final TeamMemberRepository teamMemberRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public SkillVariantEntity create(UUID skillId, int versionNumber, String tool, String instructions,
                                     AlexandriaUserDetails userDetails) {
        // Load skill (visibility check included)
        SkillEntity skill = skillService.getById(skillId, userDetails);

        // Enforce edit authority
        enforceEditAuthority(skill, userDetails);

        // Validate tool is in supported list
        validateTool(tool);

        // Find the version
        SkillVersionEntity version = skillVersionRepository.findBySkillIdAndVersionNumber(skillId, versionNumber)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Version " + versionNumber + " not found for skill"));

        // Check unique constraint: one variant per tool per version
        if (skillVariantRepository.existsBySkillVersionIdAndTool(version.getId(), tool)) {
            throw new SkillConflictException(
                    "A variant for tool '" + tool + "' already exists on version " + versionNumber);
        }

        // Create and save variant
        SkillVariantEntity variant = new SkillVariantEntity();
        variant.setSkillVersionId(version.getId());
        variant.setTool(tool);
        variant.setInstructions(instructions);

        variant = skillVariantRepository.save(variant);

        // Fire audit event (fire-and-forget)
        fireAuditEvent(userDetails, "skill_variant.created", "skill_variant", variant.getId(),
                skillId, versionNumber, tool);

        return variant;
    }

    @Override
    @Transactional
    public SkillVariantEntity update(UUID skillId, int versionNumber, String tool, String instructions,
                                     AlexandriaUserDetails userDetails) {
        // Load skill (visibility check included)
        SkillEntity skill = skillService.getById(skillId, userDetails);

        // Enforce edit authority
        enforceEditAuthority(skill, userDetails);

        // Find the version
        SkillVersionEntity version = skillVersionRepository.findBySkillIdAndVersionNumber(skillId, versionNumber)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Version " + versionNumber + " not found for skill"));

        // Find existing variant (404 if not found)
        SkillVariantEntity variant = skillVariantRepository.findBySkillVersionIdAndTool(version.getId(), tool)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Variant for tool '" + tool + "' not found on version " + versionNumber));

        // Update instructions
        variant.setInstructions(instructions);
        variant = skillVariantRepository.save(variant);

        // Fire audit event (fire-and-forget)
        fireAuditEvent(userDetails, "skill_variant.updated", "skill_variant", variant.getId(),
                skillId, versionNumber, tool);

        return variant;
    }

    @Override
    @Transactional
    public void delete(UUID skillId, int versionNumber, String tool, AlexandriaUserDetails userDetails) {
        // Load skill (visibility check included)
        SkillEntity skill = skillService.getById(skillId, userDetails);

        // Enforce edit authority
        enforceEditAuthority(skill, userDetails);

        // Find the version
        SkillVersionEntity version = skillVersionRepository.findBySkillIdAndVersionNumber(skillId, versionNumber)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Version " + versionNumber + " not found for skill"));

        // Find existing variant (404 if not found)
        SkillVariantEntity variant = skillVariantRepository.findBySkillVersionIdAndTool(version.getId(), tool)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Variant for tool '" + tool + "' not found on version " + versionNumber));

        // Delete variant
        skillVariantRepository.delete(variant);

        // Fire audit event (fire-and-forget)
        fireAuditEvent(userDetails, "skill_variant.deleted", "skill_variant", variant.getId(),
                skillId, versionNumber, tool);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolve(UUID skillVersionId, String tool) {
        return skillVariantRepository.findBySkillVersionIdAndTool(skillVersionId, tool)
                .map(SkillVariantEntity::getInstructions)
                .orElse(null);
    }

    /**
     * Enforces that the user has edit authority over the skill (team member or admin).
     */
    private void enforceEditAuthority(SkillEntity skill, AlexandriaUserDetails userDetails) {
        if (userDetails.role().isAdmin()) {
            return;
        }
        teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userDetails.userId())
                .orElseThrow(() -> new SkillAccessDeniedException(
                        "User is not a member of the owner team and lacks administrative authority"));
    }

    /**
     * Validates that the tool identifier is in the supported list.
     */
    private void validateTool(String tool) {
        if (!SUPPORTED_TOOLS.contains(tool)) {
            throw new IllegalArgumentException(
                    "Unsupported tool: '" + tool + "'. Supported tools: " + SUPPORTED_TOOLS);
        }
    }

    /**
     * Fires an audit event using fire-and-forget pattern.
     * Failures are logged at ERROR level but never propagate.
     */
    private void fireAuditEvent(AlexandriaUserDetails userDetails, String action, String targetType,
                                UUID targetId, UUID skillId, int versionNumber, String tool) {
        try {
            auditService.record(
                    userDetails.organizationId(),
                    userDetails.userId(),
                    action,
                    targetType,
                    targetId,
                    Map.of(
                            "skillId", skillId.toString(),
                            "versionNumber", String.valueOf(versionNumber),
                            "tool", tool
                    )
            );
        } catch (Exception e) {
            log.error("Failed to record audit event '{}' for skill variant (skillId={}, version={}, tool={}): {}",
                    action, skillId, versionNumber, tool, e.getMessage(), e);
        }
    }
}
