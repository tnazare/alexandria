package com.alexandria.config;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillAccessGrantRepository;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.entity.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for edit authority enforcement in SkillSecurity.
 *
 * <p><b>Property 3: Edit authority enforcement</b> —
 * For all (role, team membership status) combinations, edit access is granted
 * if and only if the user is a team member of the skill's owning team, OR has
 * Catalog_Admin role, OR has Org_Admin role. All other combinations result in
 * canEdit returning false.</p>
 *
 * <p><b>Validates: Requirements 1.5, 3.5, 4.6, 5.8, 6.9, 8.3</b></p>
 */
@Tag("skill-crud")
@Tag("Property-3-Edit-authority-enforcement")
class SkillEditAuthorityPropertyTest {

    /**
     * Property: canEdit returns true iff user is team member OR has admin role (Catalog_Admin, Org_Admin).
     *
     * <p>Generates all (role, team membership) combinations and verifies the correct
     * authorization decision is made for each.</p>
     *
     * <b>Validates: Requirements 1.5, 3.5, 4.6, 5.8, 6.9, 8.3</b>
     */
    @Property(tries = 100)
    void editAccessGrantedIffTeamMemberOrAdmin(@ForAll("editAuthorityInputs") EditAuthorityInput input) {
        // Arrange
        SkillRepository skillRepository = mock(SkillRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);

        SkillSecurity skillSecurity = new SkillSecurity(
                skillRepository, teamMemberRepository, skillAccessGrantRepository);

        UUID skillId = input.skillId();
        UUID orgId = input.userDetails().organizationId();
        UUID userId = input.userDetails().userId();
        UUID ownerTeamId = input.ownerTeamId();

        // Stub skill exists in user's organization
        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(ownerTeamId);
        skill.setName("Test Skill");
        skill.setSlug("test-skill");
        skill.setDescription("A test skill");
        skill.setCreatedBy(UUID.randomUUID());
        when(skillRepository.findByIdAndOrganizationId(eq(skillId), eq(orgId)))
                .thenReturn(Optional.of(skill));

        // Stub team membership based on input
        if (input.isTeamMember()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(ownerTeamId);
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(eq(ownerTeamId), eq(userId)))
                    .thenReturn(Optional.of(member));
        } else {
            when(teamMemberRepository.findByTeamIdAndUserId(eq(ownerTeamId), eq(userId)))
                    .thenReturn(Optional.empty());
        }

        // Create mock Authentication
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(input.userDetails());

        // Act
        boolean result = skillSecurity.canEdit(skillId, authentication);

        // Assert — edit access granted iff admin role OR team member
        boolean isAdmin = input.userDetails().role().isAdmin();
        boolean expectedAccess = isAdmin || input.isTeamMember();

        assertThat(result)
                .as("Role=%s, isTeamMember=%s → expected canEdit=%s but got %s",
                        input.userDetails().role(), input.isTeamMember(), expectedAccess, result)
                .isEqualTo(expectedAccess);
    }

    /**
     * Property: canEdit returns false when authentication is null.
     *
     * <b>Validates: Requirements 1.5, 8.3</b>
     */
    @Property(tries = 10)
    void editAccessDeniedWhenAuthenticationIsNull(@ForAll("skillIds") UUID skillId) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);

        SkillSecurity skillSecurity = new SkillSecurity(
                skillRepository, teamMemberRepository, skillAccessGrantRepository);

        boolean result = skillSecurity.canEdit(skillId, null);

        assertThat(result).isFalse();
    }

    /**
     * Property: canEdit returns false when principal is not AlexandriaUserDetails.
     *
     * <b>Validates: Requirements 1.5, 8.3</b>
     */
    @Property(tries = 10)
    void editAccessDeniedWhenPrincipalIsNotAlexandriaUserDetails(@ForAll("skillIds") UUID skillId) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);

        SkillSecurity skillSecurity = new SkillSecurity(
                skillRepository, teamMemberRepository, skillAccessGrantRepository);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");

        boolean result = skillSecurity.canEdit(skillId, authentication);

        assertThat(result).isFalse();
    }

    /**
     * Property: canEdit returns false when skill does not exist in user's organization.
     *
     * <b>Validates: Requirements 1.5, 8.3</b>
     */
    @Property(tries = 50)
    void editAccessDeniedWhenSkillNotFoundInOrg(@ForAll("nonAdminEditInputs") EditAuthorityInput input) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);

        SkillSecurity skillSecurity = new SkillSecurity(
                skillRepository, teamMemberRepository, skillAccessGrantRepository);

        UUID orgId = input.userDetails().organizationId();

        // Stub skill NOT found in user's organization
        when(skillRepository.findByIdAndOrganizationId(eq(input.skillId()), eq(orgId)))
                .thenReturn(Optional.empty());

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(input.userDetails());

        boolean result = skillSecurity.canEdit(input.skillId(), authentication);

        assertThat(result).isFalse();
    }

    // --- Test data model ---

    record EditAuthorityInput(
            UUID skillId,
            UUID ownerTeamId,
            boolean isTeamMember,
            AlexandriaUserDetails userDetails
    ) {}

    // --- Providers ---

    @Provide
    Arbitrary<EditAuthorityInput> editAuthorityInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> ownerTeamIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Boolean> teamMemberships = Arbitraries.of(true, false);
        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.values())
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@test.com"));

        return Combinators.combine(skillIds, ownerTeamIds, teamMemberships, users)
                .as(EditAuthorityInput::new);
    }

    @Provide
    Arbitrary<EditAuthorityInput> nonAdminEditInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> ownerTeamIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Boolean> teamMemberships = Arbitraries.of(true, false);
        // Only non-admin roles for this property — admin bypass means they wouldn't reach the repo call
        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Read_Only_Auditor, Role.Skill_Consumer, Role.Skill_Author,
                        Role.Reviewer, Role.Team_Owner)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@test.com"));

        return Combinators.combine(skillIds, ownerTeamIds, teamMemberships, users)
                .as(EditAuthorityInput::new);
    }

    @Provide
    Arbitrary<UUID> skillIds() {
        return Arbitraries.create(UUID::randomUUID);
    }
}
