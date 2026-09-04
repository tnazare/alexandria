package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillTagEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.user.entity.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of skill lifecycle operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillVariantRepository skillVariantRepository;
    private final SkillAccessGrantRepository skillAccessGrantRepository;
    private final SkillSlugGenerator skillSlugGenerator;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final SkillTagRepository skillTagRepository;
    private final TaxonomyEntryRepository taxonomyEntryRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public SkillEntity create(String name, String description, UUID ownerTeamId,
                              String canonicalInstructions, AlexandriaUserDetails userDetails) {

        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();

        // 1. Validate owner team exists in the user's organization
        teamRepository.findByIdAndOrganizationId(ownerTeamId, orgId)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Team not found in organization"));

        // 2. Validate user is a member of the owner team OR has admin role
        if (!userDetails.role().isAdmin()) {
            teamMemberRepository.findByTeamIdAndUserId(ownerTeamId, userId)
                    .orElseThrow(() -> new SkillAccessDeniedException(
                            "User is not a member of the owner team and lacks administrative authority"));
        }

        // 3. Generate unique slug
        String slug = skillSlugGenerator.generateUniqueSlug(name, orgId, skillRepository);

        // 4. Create SkillEntity
        SkillEntity skill = new SkillEntity();
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(ownerTeamId);
        skill.setName(name);
        skill.setSlug(slug);
        skill.setDescription(description);
        skill.setLifecycleStatus("Draft");
        skill.setVisibility("private");
        skill.setCreatedBy(userId);

        skill = skillRepository.save(skill);

        // 5. Create initial SkillVersionEntity
        SkillVersionEntity version = new SkillVersionEntity();
        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions(canonicalInstructions);
        version.setChangelog("Initial draft");

        version = skillVersionRepository.save(version);

        // 6. Set current version ID on skill
        skill.setCurrentVersionId(version.getId());
        skill = skillRepository.save(skill);

        // 7. Fire audit event
        auditService.record(orgId, userId, "skill.created", "skill", skill.getId(),
                Map.of(
                        "name", name,
                        "slug", slug,
                        "ownerTeamId", ownerTeamId.toString(),
                        "authorUserId", userId.toString()
                ));

        // 8. Rebuild search vector to include A(name)+B(description)+C(tags)+D(instructions)
        //    Must be after save() so name/description columns are current in the DB.
        rebuildSearchVector(skill);

        return skill;
    }

    @Override
    @Transactional(readOnly = true)
    public SkillEntity getById(UUID skillId, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();

        SkillEntity skill = skillRepository.findByIdAndOrganizationId(skillId, orgId)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        enforceVisibility(skill, userDetails);
        return skill;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SkillEntity> list(String status, UUID teamId, String searchQuery,
                                  boolean includeArchived, AlexandriaUserDetails userDetails, Pageable pageable) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();
        boolean isOrgAdmin = userDetails.role() == Role.Org_Admin;

        return skillRepository.findVisibleSkills(orgId, userId, isOrgAdmin,
                status, null, null, teamId, null, null, null, null, searchQuery, includeArchived, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public SkillEntity getBySlug(String slug, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();

        SkillEntity skill = skillRepository.findByOrganizationIdAndSlug(orgId, slug)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        enforceVisibility(skill, userDetails);
        return skill;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SkillVersionEntity> listVersions(UUID skillId, AlexandriaUserDetails userDetails, Pageable pageable) {
        // Verify the user can see the skill first
        getById(skillId, userDetails);

        return skillVersionRepository.findBySkillIdOrderByVersionNumberDesc(skillId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public SkillVersionEntity getVersion(UUID skillId, int versionNumber, AlexandriaUserDetails userDetails) {
        // Verify the user can see the skill first
        getById(skillId, userDetails);

        return skillVersionRepository.findBySkillIdAndVersionNumber(skillId, versionNumber)
                .orElseThrow(() -> new SkillNotFoundException(
                        "Version " + versionNumber + " not found for skill"));
    }

    @Override
    @Transactional
    public SkillEntity update(UUID skillId, UpdateParams params, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();

        // 1. Load skill by ID and org (404 if not found)
        SkillEntity skill = skillRepository.findByIdAndOrganizationId(skillId, orgId)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        // 2. Enforce edit authority (team member OR admin, else 403)
        if (!userDetails.role().isAdmin()) {
            teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId)
                    .orElseThrow(() -> new SkillAccessDeniedException(
                            "User is not a member of the owner team and lacks administrative authority"));
        }

        // 3. Check lifecycle status — reject if Deprecated/Archived/Blocked
        String status = skill.getLifecycleStatus();
        if (Set.of("Deprecated", "Archived", "Blocked").contains(status)) {
            throw new SkillConflictException(
                    "Cannot edit skill in " + status + " status");
        }

        // Determine current version
        SkillVersionEntity currentVersion = skillVersionRepository.findById(skill.getCurrentVersionId())
                .orElseThrow(() -> new SkillNotFoundException("Current version not found"));

        SkillVersionEntity targetVersion;
        List<String> modifiedFields = new ArrayList<>();

        if ("Draft".equals(status)) {
            // 4. Draft: update in-place
            targetVersion = currentVersion;

            // Update skill-level fields if provided
            if (params.name() != null) {
                skill.setName(params.name());
                String newSlug = skillSlugGenerator.generateUniqueSlug(params.name(), orgId, skillRepository);
                skill.setSlug(newSlug);
                modifiedFields.add("name");
                modifiedFields.add("slug");
            }
            if (params.description() != null) {
                skill.setDescription(params.description());
                modifiedFields.add("description");
            }
            if (params.riskLevel() != null) {
                skill.setRiskLevel(params.riskLevel());
                modifiedFields.add("riskLevel");
            }

            // Update version-level fields
            updateVersionFields(targetVersion, params, modifiedFields);

            skillVersionRepository.save(targetVersion);
            skill = skillRepository.save(skill);

            // Audit: "skill.updated"
            auditService.record(orgId, userId, "skill.updated", "skill", skill.getId(),
                    Map.of("modifiedFields", String.join(",", modifiedFields)));

        } else {
            // 5. Published/Team_Approved/Company_Approved: create new version
            // Require changelog
            if (params.changelog() == null || params.changelog().isBlank()
                    || params.changelog().length() > 2000) {
                throw new IllegalArgumentException(
                        "Changelog is required (1-2000 characters) when editing a published skill");
            }

            // Create new version with incremented version number
            int nextVersionNumber = skillVersionRepository.countBySkillId(skillId) + 1;

            targetVersion = new SkillVersionEntity();
            targetVersion.setSkillId(skillId);
            targetVersion.setVersionNumber(nextVersionNumber);

            // Copy fields from current version as baseline
            targetVersion.setCanonicalInstructions(currentVersion.getCanonicalInstructions());
            targetVersion.setApplicability(currentVersion.getApplicability());
            targetVersion.setLimitations(currentVersion.getLimitations());
            targetVersion.setWhenToUse(currentVersion.getWhenToUse());
            targetVersion.setExamples(currentVersion.getExamples());
            targetVersion.setSupportedTools(new ArrayList<>(currentVersion.getSupportedTools()));
            targetVersion.setChangelog(params.changelog());

            // Apply version-level updates from params
            updateVersionFields(targetVersion, params, modifiedFields);

            targetVersion = skillVersionRepository.save(targetVersion);

            // Revert skill lifecycle_status to Draft
            skill.setLifecycleStatus("Draft");
            skill.setCurrentVersionId(targetVersion.getId());

            // Update skill-level fields if provided
            if (params.name() != null) {
                skill.setName(params.name());
                String newSlug = skillSlugGenerator.generateUniqueSlug(params.name(), orgId, skillRepository);
                skill.setSlug(newSlug);
            }
            if (params.description() != null) {
                skill.setDescription(params.description());
            }
            if (params.riskLevel() != null) {
                skill.setRiskLevel(params.riskLevel());
            }

            skill = skillRepository.save(skill);

            // Audit: "skill_version.created"
            auditService.record(orgId, userId, "skill_version.created", "skill_version", targetVersion.getId(),
                    Map.of(
                            "skillId", skillId.toString(),
                            "versionNumber", String.valueOf(nextVersionNumber)
                    ));
        }

        // 6. Handle tags: if tagIds provided, validate and reinsert
        if (params.tagIds() != null) {
            handleTagUpdate(skill, params.tagIds(), orgId);
        }

        // 7. Rebuild search vector after all changes (tags + content) are persisted.
        //    Called once here — NOT inside handleTagUpdate — to avoid a double rebuild.
        rebuildSearchVector(skill);

        return skill;
    }

    /**
     * Applies non-null version-level fields from params onto the target version entity.
     */
    private void updateVersionFields(SkillVersionEntity version, UpdateParams params, List<String> modifiedFields) {
        if (params.canonicalInstructions() != null) {
            version.setCanonicalInstructions(params.canonicalInstructions());
            modifiedFields.add("canonicalInstructions");
        }
        if (params.applicability() != null) {
            version.setApplicability(params.applicability());
            modifiedFields.add("applicability");
        }
        if (params.limitations() != null) {
            version.setLimitations(params.limitations());
            modifiedFields.add("limitations");
        }
        if (params.whenToUse() != null) {
            version.setWhenToUse(params.whenToUse());
            modifiedFields.add("whenToUse");
        }
        if (params.examples() != null) {
            version.setExamples(params.examples());
            modifiedFields.add("examples");
        }
        if (params.changelog() != null) {
            version.setChangelog(params.changelog());
            modifiedFields.add("changelog");
        }
        if (params.supportedTools() != null) {
            version.setSupportedTools(params.supportedTools());
            modifiedFields.add("supportedTools");
        }
    }

    /**
     * Validates tag IDs against the org's taxonomy entries and replaces all skill tags.
     */
    private void handleTagUpdate(SkillEntity skill, List<UUID> tagIds, UUID orgId) {
        if (tagIds.isEmpty()) {
            skillTagRepository.deleteBySkillId(skill.getId());
            return;
        }

        // Validate all tag IDs are active taxonomy entries in the org
        List<TaxonomyEntryEntity> entries = taxonomyEntryRepository.findByOrganizationIdAndIdIn(orgId, tagIds);
        List<UUID> validActiveIds = entries.stream()
                .filter(TaxonomyEntryEntity::isActive)
                .map(TaxonomyEntryEntity::getId)
                .toList();

        if (validActiveIds.size() != tagIds.size()) {
            throw new IllegalArgumentException(
                    "One or more tag IDs are invalid or inactive in this organization");
        }

        // Delete existing and reinsert
        skillTagRepository.deleteBySkillId(skill.getId());

        for (UUID tagId : tagIds) {
            SkillTagEntity tag = new SkillTagEntity();
            tag.setSkillId(skill.getId());
            tag.setTaxonomyEntryId(tagId);
            skillTagRepository.save(tag);
        }
    }

    @Override
    @Transactional
    public SkillEntity publish(UUID skillId, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();

        // 1. Load skill by ID and org (404 if not found)
        SkillEntity skill = skillRepository.findByIdAndOrganizationId(skillId, orgId)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        // 2. Enforce edit authority (team member OR admin, else 403)
        if (!userDetails.role().isAdmin()) {
            teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId)
                    .orElseThrow(() -> new SkillAccessDeniedException(
                            "User is not a member of the owner team and lacks administrative authority"));
        }

        // 3. If lifecycle_status is NOT "Draft" → throw SkillConflictException (409)
        if (!"Draft".equals(skill.getLifecycleStatus())) {
            throw new SkillConflictException(
                    "Only skills in Draft status can be published. Current status: " + skill.getLifecycleStatus());
        }

        // 4. Validate required metadata on current version
        SkillVersionEntity currentVersion = skillVersionRepository.findById(skill.getCurrentVersionId())
                .orElseThrow(() -> new SkillNotFoundException("Current version not found"));

        List<String> missingFields = new ArrayList<>();

        // 4a. At least one task_type tag
        List<SkillTagEntity> tags = skillTagRepository.findBySkillId(skillId);
        boolean hasTaskTypeTag = false;
        if (!tags.isEmpty()) {
            List<UUID> taxonomyEntryIds = tags.stream()
                    .map(SkillTagEntity::getTaxonomyEntryId)
                    .toList();
            List<TaxonomyEntryEntity> entries = taxonomyEntryRepository.findAllById(taxonomyEntryIds);
            hasTaskTypeTag = entries.stream()
                    .anyMatch(entry -> "task_type".equals(entry.getTagType()));
        }
        if (!hasTaskTypeTag) {
            missingFields.add("task_type_tag");
        }

        // 4b. Visibility is set (non-null and non-empty)
        if (skill.getVisibility() == null || skill.getVisibility().isBlank()) {
            missingFields.add("visibility");
        }

        // 4c. At least one supported tool in the current version
        if (currentVersion.getSupportedTools() == null || currentVersion.getSupportedTools().isEmpty()) {
            missingFields.add("supportedTools");
        }

        // 4d. risk_level is set (non-null) on the skill
        if (skill.getRiskLevel() == null || skill.getRiskLevel().isBlank()) {
            missingFields.add("riskLevel");
        }

        // 4e. applicability is non-null and non-empty on the current version
        if (currentVersion.getApplicability() == null || currentVersion.getApplicability().isBlank()) {
            missingFields.add("applicability");
        }

        // 4f. At least one example (examples field non-null and non-empty) on the current version
        if (currentVersion.getExamples() == null || currentVersion.getExamples().isBlank()) {
            missingFields.add("examples");
        }

        // 5. If any metadata is missing, throw PublishValidationException (422)
        if (!missingFields.isEmpty()) {
            throw new PublishValidationException(missingFields);
        }

        // 6. Set lifecycle_status to "Published", set publishedAt on current version
        skill.setLifecycleStatus("Published");
        skill = skillRepository.save(skill);

        currentVersion.setPublishedAt(Instant.now());
        skillVersionRepository.save(currentVersion);

        // 7. Fire audit event "skill.published"
        auditService.record(orgId, userId, "skill.published", "skill", skill.getId(),
                Map.of(
                        "versionNumber", String.valueOf(currentVersion.getVersionNumber()),
                        "publishedAt", currentVersion.getPublishedAt().toString()
                ));

        return skill;
    }

    @Override
    @Transactional
    public SkillEntity changeVisibility(UUID skillId, String visibility, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();

        // 1. Load skill by ID and org (404 if not found)
        SkillEntity skill = skillRepository.findByIdAndOrganizationId(skillId, orgId)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        // 2. Enforce edit authority (team member OR admin, else 403)
        if (!userDetails.role().isAdmin()) {
            teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId)
                    .orElseThrow(() -> new SkillAccessDeniedException(
                            "User is not a member of the owner team and lacks administrative authority"));
        }

        // 3. Validate visibility value (400 via IllegalArgumentException)
        Set<String> validVisibilities = Set.of("private", "team", "organization", "restricted");
        if (!validVisibilities.contains(visibility)) {
            throw new IllegalArgumentException(
                    "Invalid visibility value: " + visibility + ". Must be one of: private, team, organization, restricted");
        }

        // 4. Update visibility field
        String oldVisibility = skill.getVisibility();
        skill.setVisibility(visibility);
        skill = skillRepository.save(skill);

        // 5. Fire audit event "skill.visibility_changed"
        auditService.record(orgId, userId, "skill.visibility_changed", "skill", skill.getId(),
                Map.of(
                        "oldVisibility", oldVisibility,
                        "newVisibility", visibility
                ));

        return skill;
    }

    @Override
    @Transactional
    public void delete(UUID skillId, AlexandriaUserDetails userDetails) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();

        // 1. Load skill by ID and org (404 if not found)
        SkillEntity skill = skillRepository.findByIdAndOrganizationId(skillId, orgId)
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));

        // 2. Enforce edit authority (team member OR admin, else 403)
        if (!userDetails.role().isAdmin()) {
            teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId)
                    .orElseThrow(() -> new SkillAccessDeniedException(
                            "User is not a member of the owner team and lacks administrative authority"));
        }

        // 3. If lifecycle_status is NOT "Draft" → throw SkillConflictException (409)
        if (!"Draft".equals(skill.getLifecycleStatus())) {
            throw new SkillConflictException("Only Draft skills can be deleted");
        }

        // 4. Delete associated data in order
        // 4a. Delete access grants
        skillAccessGrantRepository.deleteBySkillId(skillId);

        // 4b. Delete tags
        skillTagRepository.deleteBySkillId(skillId);

        // 4c. Delete variants (via versions)
        List<SkillVersionEntity> versions = skillVersionRepository.findBySkillId(skillId);
        for (SkillVersionEntity version : versions) {
            skillVariantRepository.deleteBySkillVersionId(version.getId());
        }

        // 4d. Delete versions
        // First clear the current_version_id FK on skill to avoid constraint violations
        skill.setCurrentVersionId(null);
        skillRepository.save(skill);
        skillVersionRepository.deleteBySkillId(skillId);

        // 4e. Delete the skill itself
        skillRepository.delete(skill);

        // 5. Fire audit event "skill.deleted" (allow deletion to succeed even if audit fails)
        try {
            auditService.record(orgId, userId, "skill.deleted", "skill", skillId,
                    Map.of(
                            "name", skill.getName(),
                            "slug", skill.getSlug()
                    ));
        } catch (Exception e) {
            log.warn("Failed to record audit event for skill deletion: skillId={}", skillId, e);
        }
    }

    /**
     * Rebuilds the full-text search vector for a skill to include all four weights:
     * name (A), description (B), active tag values (C), and current-version
     * canonical instructions (D).
     *
     * <p>Must be called <em>after</em> {@code skillRepository.save()} so that the
     * name and description columns in the DB are current. Must NOT be called from
     * within {@code handleTagUpdate} — call it once at the {@code create}/{@code update}
     * boundary after all changes are applied.</p>
     */
    private void rebuildSearchVector(SkillEntity skill) {
        // Gather active tag values
        List<SkillTagEntity> tagEntities = skillTagRepository.findBySkillId(skill.getId());
        String tagValues = "";
        if (!tagEntities.isEmpty()) {
            List<UUID> taxonomyIds = tagEntities.stream()
                    .map(SkillTagEntity::getTaxonomyEntryId)
                    .toList();
            tagValues = taxonomyEntryRepository.findAllById(taxonomyIds).stream()
                    .filter(TaxonomyEntryEntity::isActive)
                    .map(TaxonomyEntryEntity::getValue)
                    .sorted()
                    .collect(Collectors.joining(" "));
        }

        // Gather canonical instructions from the current version (null-safe)
        String instructions = "";
        if (skill.getCurrentVersionId() != null) {
            instructions = skillVersionRepository.findById(skill.getCurrentVersionId())
                    .map(SkillVersionEntity::getCanonicalInstructions)
                    .orElse("");
            if (instructions == null) {
                instructions = "";
            }
        }

        skillRepository.rebuildSearchVector(skill.getId(), tagValues, instructions);
    }

    /**
     * Enforces visibility rules for a single skill.
     * Throws SkillNotFoundException if the user cannot see the skill (prevents enumeration).
     */
    private void enforceVisibility(SkillEntity skill, AlexandriaUserDetails userDetails) {
        UUID userId = userDetails.userId();

        // Org_Admin can see everything
        if (userDetails.role() == Role.Org_Admin) {
            return;
        }

        String visibility = skill.getVisibility();

        switch (visibility) {
            case "organization" -> {
                // All users in the same org can see it — already scoped by org
            }
            case "team" -> {
                if (!skill.getCreatedBy().equals(userId)
                        && teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId).isEmpty()) {
                    throw new SkillNotFoundException("Skill not found");
                }
            }
            case "private" -> {
                if (!skill.getCreatedBy().equals(userId)) {
                    throw new SkillNotFoundException("Skill not found");
                }
            }
            case "restricted" -> {
                if (!skill.getCreatedBy().equals(userId)
                        && !skillAccessGrantRepository.existsBySkillIdAndUserId(skill.getId(), userId)) {
                    throw new SkillNotFoundException("Skill not found");
                }
            }
            default -> throw new SkillNotFoundException("Skill not found");
        }
    }
}
