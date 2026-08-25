package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
}
