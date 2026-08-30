package com.alexandria.skill.lifecycle;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillConflictException;
import com.alexandria.skill.SkillNotFoundException;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.entity.SkillEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of skill governance lifecycle transitions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLifecycleServiceImpl implements SkillLifecycleService {

    private final SkillRepository skillRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public SkillEntity deprecate(UUID skillId, String reason, AlexandriaUserDetails user) {
        SkillEntity skill = load(skillId, user);
        String from = skill.getLifecycleStatus();
        requireAllowed(from, LifecycleAction.DEPRECATE);

        capturePriorStatus(skill, from);
        skill.setLifecycleStatus(SkillLifecycleTransition.DEPRECATED);
        skill.setDeprecationReason(reason);
        skill = skillRepository.save(skill);

        Map<String, Object> metadata = baseMetadata(from, SkillLifecycleTransition.DEPRECATED, skill.getPriorStatus());
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        fireAudit(user, "skill.deprecated", skill.getId(), metadata);
        return skill;
    }

    @Override
    @Transactional
    public SkillEntity block(UUID skillId, AlexandriaUserDetails user) {
        SkillEntity skill = load(skillId, user);
        String from = skill.getLifecycleStatus();
        requireAllowed(from, LifecycleAction.BLOCK);

        capturePriorStatus(skill, from);
        skill.setLifecycleStatus(SkillLifecycleTransition.BLOCKED);
        skill = skillRepository.save(skill);

        fireAudit(user, "skill.blocked", skill.getId(),
                baseMetadata(from, SkillLifecycleTransition.BLOCKED, skill.getPriorStatus()));
        return skill;
    }

    @Override
    @Transactional
    public SkillEntity archive(UUID skillId, AlexandriaUserDetails user) {
        SkillEntity skill = load(skillId, user);
        String from = skill.getLifecycleStatus();
        requireAllowed(from, LifecycleAction.ARCHIVE);

        capturePriorStatus(skill, from);
        skill.setLifecycleStatus(SkillLifecycleTransition.ARCHIVED);
        skill = skillRepository.save(skill);

        fireAudit(user, "skill.archived", skill.getId(),
                baseMetadata(from, SkillLifecycleTransition.ARCHIVED, skill.getPriorStatus()));
        return skill;
    }

    @Override
    @Transactional
    public SkillEntity restore(UUID skillId, AlexandriaUserDetails user) {
        SkillEntity skill = load(skillId, user);
        String from = skill.getLifecycleStatus();
        requireAllowed(from, LifecycleAction.RESTORE);

        String target = skill.getPriorStatus() != null
                ? skill.getPriorStatus()
                : SkillLifecycleTransition.DRAFT;

        skill.setLifecycleStatus(target);
        skill.setDeprecationReason(null);
        skill.setPriorStatus(null);
        skill = skillRepository.save(skill);

        fireAudit(user, "skill.restored", skill.getId(), baseMetadata(from, target, null));
        return skill;
    }

    // --- helpers ---

    private SkillEntity load(UUID skillId, AlexandriaUserDetails user) {
        return skillRepository.findByIdAndOrganizationId(skillId, user.organizationId())
                .orElseThrow(() -> new SkillNotFoundException("Skill not found"));
    }

    private void requireAllowed(String currentStatus, LifecycleAction action) {
        if (!SkillLifecycleTransition.isAllowed(currentStatus, action)) {
            throw new SkillConflictException(
                    "Cannot " + action.name().toLowerCase() + " a skill in " + currentStatus + " status");
        }
    }

    /**
     * Records the prior status only when the skill is currently in an active state.
     * When transitioning between governed states (e.g. blocking a deprecated skill), the
     * existing prior status — the original active state — is preserved so that a later
     * restore returns the skill to that active state rather than to a governed one.
     */
    private void capturePriorStatus(SkillEntity skill, String currentStatus) {
        if (SkillLifecycleTransition.isActive(currentStatus)) {
            skill.setPriorStatus(currentStatus);
        }
    }

    private Map<String, Object> baseMetadata(String fromStatus, String toStatus, String priorStatus) {
        Map<String, Object> m = new HashMap<>();
        m.put("fromStatus", fromStatus);
        m.put("toStatus", toStatus);
        if (priorStatus != null) {
            m.put("priorStatus", priorStatus);
        }
        return m;
    }

    private void fireAudit(AlexandriaUserDetails user, String action, UUID skillId, Map<String, Object> metadata) {
        try {
            auditService.record(user.organizationId(), user.userId(), action, "skill", skillId, metadata);
        } catch (Exception e) {
            log.error("Failed to record audit event '{}' for skill {}: {}", action, skillId, e.getMessage(), e);
        }
    }
}
