package com.alexandria.skill.lifecycle;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillConflictException;
import com.alexandria.skill.SkillNotFoundException;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;

/**
 * Behavior tests for {@link SkillLifecycleServiceImpl}.
 *
 * <p>Validates design Properties 2, 3, 4, 6, and 8 with mocked repository and audit service.</p>
 *
 * <ul>
 *   <li>Property 2: deprecate/block/archive set target + record prior status</li>
 *   <li>Property 3: restore round-trip returns to original active state, clears governance fields</li>
 *   <li>Property 4: version neutrality (currentVersionId unchanged)</li>
 *   <li>Property 6: block-from-deprecated preserves the original active prior status</li>
 *   <li>Property 8: exactly one audit event per transition with the correct action</li>
 * </ul>
 */
@Tag("skill-lifecycle")
class SkillLifecycleServicePropertyTest {

    private SkillRepository skillRepository;
    private AuditService auditService;
    private SkillLifecycleServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private AlexandriaUserDetails user;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        auditService = mock(AuditService.class);
        service = new SkillLifecycleServiceImpl(skillRepository, auditService);
        user = new AlexandriaUserDetails(userId, orgId, Role.Catalog_Admin, "admin@test.com");
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SkillEntity skillWith(String status) {
        var skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(UUID.randomUUID());
        skill.setName("Test");
        skill.setSlug("test");
        skill.setDescription("desc");
        skill.setLifecycleStatus(status);
        skill.setVisibility("organization");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);
        return skill;
    }

    private void stub(SkillEntity skill) {
        when(skillRepository.findByIdAndOrganizationId(skillId, orgId)).thenReturn(Optional.of(skill));
    }

    // --- Property 2: set target + record prior status ---

    @Test
    @DisplayName("Property 2: deprecate sets Deprecated, records prior status and reason")
    void deprecateSetsTargetAndPrior() {
        stub(skillWith("Published"));
        SkillEntity result = service.deprecate(skillId, "outdated", user);
        assertThat(result.getLifecycleStatus()).isEqualTo("Deprecated");
        assertThat(result.getPriorStatus()).isEqualTo("Published");
        assertThat(result.getDeprecationReason()).isEqualTo("outdated");
    }

    @Test
    @DisplayName("Property 2: block sets Blocked and records prior status")
    void blockSetsTargetAndPrior() {
        stub(skillWith("Published"));
        SkillEntity result = service.block(skillId, user);
        assertThat(result.getLifecycleStatus()).isEqualTo("Blocked");
        assertThat(result.getPriorStatus()).isEqualTo("Published");
    }

    @Test
    @DisplayName("Property 2: archive sets Archived and records prior status")
    void archiveSetsTargetAndPrior() {
        stub(skillWith("Draft"));
        SkillEntity result = service.archive(skillId, user);
        assertThat(result.getLifecycleStatus()).isEqualTo("Archived");
        assertThat(result.getPriorStatus()).isEqualTo("Draft");
    }

    // --- Property 3: restore round-trip ---

    @Test
    @DisplayName("Property 3: restore returns to prior status and clears governance fields")
    void restoreRoundTrip() {
        var skill = skillWith("Published");
        stub(skill);
        service.deprecate(skillId, "reason", user);
        // deprecate mutated the same instance; now restore
        SkillEntity restored = service.restore(skillId, user);
        assertThat(restored.getLifecycleStatus()).isEqualTo("Published");
        assertThat(restored.getDeprecationReason()).isNull();
        assertThat(restored.getPriorStatus()).isNull();
    }

    @Test
    @DisplayName("Property 3: restore with no prior status falls back to Draft")
    void restoreNoPriorFallsBackToDraft() {
        var skill = skillWith("Blocked"); // seeded directly in a governed state, no prior
        stub(skill);
        SkillEntity restored = service.restore(skillId, user);
        assertThat(restored.getLifecycleStatus()).isEqualTo("Draft");
        assertThat(restored.getPriorStatus()).isNull();
    }

    // --- Property 4: version neutrality ---

    @Test
    @DisplayName("Property 4: transitions do not change currentVersionId")
    void versionNeutrality() {
        var skill = skillWith("Published");
        stub(skill);
        UUID before = skill.getCurrentVersionId();
        service.deprecate(skillId, null, user);
        assertThat(skill.getCurrentVersionId()).isEqualTo(before);
        service.block(skillId, user);
        assertThat(skill.getCurrentVersionId()).isEqualTo(before);
        service.restore(skillId, user);
        assertThat(skill.getCurrentVersionId()).isEqualTo(before);
    }

    // --- Property 6: block-from-deprecated preserves original active prior ---

    @Test
    @DisplayName("Property 6: blocking a deprecated skill preserves the original active prior state")
    void blockFromDeprecatedPreservesOriginalPrior() {
        var skill = skillWith("Published");
        stub(skill);
        service.deprecate(skillId, "reason", user); // prior = Published, status = Deprecated
        service.block(skillId, user);               // status = Blocked, prior should stay Published
        assertThat(skill.getPriorStatus()).isEqualTo("Published");
        SkillEntity restored = service.restore(skillId, user);
        assertThat(restored.getLifecycleStatus()).isEqualTo("Published");
    }

    // --- Property 8: exactly one audit event per transition ---

    @Test
    @DisplayName("Property 8: each transition records exactly one audit event with the right action")
    void auditPerTransition() {
        var skill = skillWith("Published");
        stub(skill);

        service.deprecate(skillId, "r", user);
        verify(auditService, times(1)).record(eq(orgId), eq(userId), eq("skill.deprecated"), eq("skill"), eq(skillId), any());

        service.block(skillId, user);
        verify(auditService, times(1)).record(eq(orgId), eq(userId), eq("skill.blocked"), eq("skill"), eq(skillId), any());

        service.restore(skillId, user);
        verify(auditService, times(1)).record(eq(orgId), eq(userId), eq("skill.restored"), eq("skill"), eq(skillId), any());
    }

    // --- Error paths ---

    @Test
    @DisplayName("illegal transition throws SkillConflictException and records no audit")
    void illegalTransitionRejected() {
        stub(skillWith("Blocked"));
        assertThatThrownBy(() -> service.block(skillId, user)).isInstanceOf(SkillConflictException.class);
        assertThatThrownBy(() -> service.deprecate(skillId, null, user)).isInstanceOf(SkillConflictException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing skill throws SkillNotFoundException")
    void missingSkill() {
        when(skillRepository.findByIdAndOrganizationId(skillId, orgId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deprecate(skillId, null, user)).isInstanceOf(SkillNotFoundException.class);
    }
}
