package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.user.entity.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Implementation of skill lifecycle operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillAccessGrantRepository skillAccessGrantRepository;
    private final SkillSlugGenerator skillSlugGenerator;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
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
                                  AlexandriaUserDetails userDetails, Pageable pageable) {
        UUID orgId = userDetails.organizationId();
        UUID userId = userDetails.userId();
        boolean isOrgAdmin = userDetails.role() == Role.Org_Admin;

        return skillRepository.findVisibleSkills(orgId, userId, isOrgAdmin,
                status, teamId, searchQuery, pageable);
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
