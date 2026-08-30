package com.alexandria.skill;

import com.alexandria.skill.dto.SkillResponse;
import com.alexandria.skill.dto.SkillSummaryResponse;
import com.alexandria.skill.dto.SkillVersionResponse;
import com.alexandria.skill.dto.TagResponse;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillTagEntity;
import com.alexandria.skill.entity.SkillVariantEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import com.alexandria.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles skill response DTOs from persisted entities.
 *
 * <p>Centralizes the mapping between the persistence model (skill, version,
 * variant, tag entities) and the API response DTOs so that both the skill
 * controller and the variant controller produce consistent output.</p>
 */
@Component
@RequiredArgsConstructor
public class SkillResponseMapper {

    private final SkillVersionRepository skillVersionRepository;
    private final SkillVariantRepository skillVariantRepository;
    private final SkillTagRepository skillTagRepository;
    private final TaxonomyEntryRepository taxonomyEntryRepository;
    private final TeamRepository teamRepository;

    /**
     * Builds a full {@link SkillResponse} including current version content,
     * tags, supported tools, and variants.
     */
    @Transactional(readOnly = true)
    public SkillResponse toSkillResponse(SkillEntity skill) {
        SkillVersionEntity currentVersion = skill.getCurrentVersionId() == null
                ? null
                : skillVersionRepository.findById(skill.getCurrentVersionId()).orElse(null);

        List<TagResponse> tags = resolveTags(skill.getId(), skill.getOrganizationId());
        String teamName = resolveTeamName(skill.getOwnerTeamId(), skill.getOrganizationId());

        List<SkillResponse.VariantSummary> variants = currentVersion == null
                ? List.of()
                : resolveVariants(currentVersion.getId());

        List<String> supportedTools = currentVersion == null
                ? List.of()
                : List.copyOf(currentVersion.getSupportedTools());

        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getSlug(),
                skill.getDescription(),
                skill.getOwnerTeamId(),
                teamName,
                skill.getLifecycleStatus(),
                skill.getVisibility(),
                skill.getRiskLevel(),
                currentVersion == null ? 0 : currentVersion.getVersionNumber(),
                currentVersion == null ? null : currentVersion.getCanonicalInstructions(),
                currentVersion == null ? null : currentVersion.getApplicability(),
                currentVersion == null ? null : currentVersion.getLimitations(),
                currentVersion == null ? null : currentVersion.getWhenToUse(),
                currentVersion == null ? null : currentVersion.getExamples(),
                currentVersion == null ? null : currentVersion.getChangelog(),
                tags,
                supportedTools,
                variants,
                skill.getCreatedBy(),
                skill.getCreatedAt(),
                skill.getUpdatedAt(),
                currentVersion == null ? null : currentVersion.getPublishedAt()
        );
    }

    /**
     * Builds a {@link SkillSummaryResponse} for list views.
     */
    @Transactional(readOnly = true)
    public SkillSummaryResponse toSummaryResponse(SkillEntity skill) {
        SkillVersionEntity currentVersion = skill.getCurrentVersionId() == null
                ? null
                : skillVersionRepository.findById(skill.getCurrentVersionId()).orElse(null);

        return new SkillSummaryResponse(
                skill.getId(),
                skill.getName(),
                skill.getSlug(),
                skill.getDescription(),
                resolveTeamName(skill.getOwnerTeamId(), skill.getOrganizationId()),
                skill.getLifecycleStatus(),
                skill.getVisibility(),
                skill.getRiskLevel(),
                currentVersion == null ? 0 : currentVersion.getVersionNumber(),
                resolveTags(skill.getId(), skill.getOrganizationId()),
                skill.getUpdatedAt()
        );
    }

    /**
     * Builds a {@link SkillVersionResponse} including its variants.
     */
    @Transactional(readOnly = true)
    public SkillVersionResponse toVersionResponse(SkillVersionEntity version) {
        return new SkillVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getCanonicalInstructions(),
                version.getApplicability(),
                version.getLimitations(),
                version.getWhenToUse(),
                version.getExamples(),
                version.getChangelog(),
                resolveVariants(version.getId()),
                version.getCreatedAt(),
                version.getPublishedAt()
        );
    }

    private List<SkillResponse.VariantSummary> resolveVariants(UUID skillVersionId) {
        return skillVariantRepository.findBySkillVersionId(skillVersionId).stream()
                .map(this::toVariantSummary)
                .toList();
    }

    private SkillResponse.VariantSummary toVariantSummary(SkillVariantEntity variant) {
        return new SkillResponse.VariantSummary(
                variant.getId(),
                variant.getTool(),
                variant.getInstructions(),
                variant.getCreatedAt(),
                variant.getUpdatedAt()
        );
    }

    private List<TagResponse> resolveTags(UUID skillId, UUID orgId) {
        List<SkillTagEntity> skillTags = skillTagRepository.findBySkillId(skillId);
        if (skillTags.isEmpty()) {
            return List.of();
        }
        List<UUID> entryIds = skillTags.stream()
                .map(SkillTagEntity::getTaxonomyEntryId)
                .toList();
        Map<UUID, TaxonomyEntryEntity> entriesById = taxonomyEntryRepository.findByOrganizationIdAndIdIn(orgId, entryIds)
                .stream()
                .collect(Collectors.toMap(TaxonomyEntryEntity::getId, Function.identity()));

        return skillTags.stream()
                .map(tag -> entriesById.get(tag.getTaxonomyEntryId()))
                .filter(entry -> entry != null)
                .map(entry -> new TagResponse(entry.getId(), entry.getTagType(), entry.getValue()))
                .toList();
    }

    private String resolveTeamName(UUID ownerTeamId, UUID orgId) {
        return teamRepository.findByIdAndOrganizationId(ownerTeamId, orgId)
                .map(team -> team.getName())
                .orElse(null);
    }
}
