package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.entity.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for visibility enforcement in SkillServiceImpl.
 *
 * <p><b>Property 5: Visibility enforcement</b> —
 * for any combination of (visibility, user role, team membership, creator status, access grant status),
 * the system grants or denies view access exactly per visibility rules:
 * <ul>
 *   <li>"organization" → all users in the org can see</li>
 *   <li>"team" → only team members and the creator can see</li>
 *   <li>"private" → only the creator can see</li>
 *   <li>"restricted" → only the creator and explicitly granted users can see</li>
 *   <li>Org_Admin → always sees everything regardless of visibility</li>
 * </ul>
 * Denied access throws SkillNotFoundException (no metadata leakage).
 * </p>
 *
 * <p><b>Validates: Requirements 2.4, 5.1, 5.2, 5.3, 5.4, 5.9</b></p>
 */
@Tag("skill-crud")
@Tag("Property-5-Visibility-enforcement")
class SkillVisibilityEnforcementPropertyTest {

    /**
     * Property: When access is expected to be granted, getById returns the skill successfully.
     *
     * Validates: Requirements 2.4, 5.1, 5.2, 5.3, 5.4
     */
    @Property(tries = 100)
    void accessGrantedWhenVisibilityRulesPermit(@ForAll("accessGrantedScenarios") VisibilityScenario scenario) {
        var fixture = setupFixture(scenario);

        SkillEntity result = fixture.service.getById(scenario.skillId(), scenario.userDetails());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(scenario.skillId());
    }

    /**
     * Property: When access is expected to be denied, getById throws SkillNotFoundException
     * with no metadata leakage (generic message only).
     *
     * Validates: Requirements 2.4, 5.1, 5.2, 5.3, 5.4, 5.9
     */
    @Property(tries = 100)
    void accessDeniedThrowsSkillNotFoundException(@ForAll("accessDeniedScenarios") VisibilityScenario scenario) {
        var fixture = setupFixture(scenario);

        assertThatThrownBy(() -> fixture.service.getById(scenario.skillId(), scenario.userDetails()))
                .isInstanceOf(SkillNotFoundException.class)
                .hasMessage("Skill not found");
    }

    /**
     * Property: Org_Admin can always see any skill regardless of visibility setting.
     *
     * Validates: Requirements 5.1, 5.2, 5.3, 5.4
     */
    @Property(tries = 100)
    void orgAdminAlwaysHasAccess(@ForAll("orgAdminScenarios") VisibilityScenario scenario) {
        var fixture = setupFixture(scenario);

        SkillEntity result = fixture.service.getById(scenario.skillId(), scenario.userDetails());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(scenario.skillId());
    }

    // --- Test infrastructure ---

    record VisibilityScenario(
            UUID skillId,
            String visibility,
            Role userRole,
            boolean isCreator,
            boolean isTeamMember,
            boolean hasAccessGrant,
            AlexandriaUserDetails userDetails,
            UUID creatorId,
            UUID ownerTeamId,
            UUID orgId
    ) {}

    record TestFixture(SkillServiceImpl service) {}

    private TestFixture setupFixture(VisibilityScenario scenario) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        SkillVersionRepository skillVersionRepository = mock(SkillVersionRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);
        SkillSlugGenerator skillSlugGenerator = mock(SkillSlugGenerator.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillTagRepository skillTagRepository = mock(SkillTagRepository.class);
        TaxonomyEntryRepository taxonomyEntryRepository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        // Set up the skill entity
        SkillEntity skill = new SkillEntity();
        skill.setId(scenario.skillId());
        skill.setOrganizationId(scenario.orgId());
        skill.setOwnerTeamId(scenario.ownerTeamId());
        skill.setName("Test Skill");
        skill.setSlug("test-skill");
        skill.setDescription("A test skill");
        skill.setLifecycleStatus("Published");
        skill.setVisibility(scenario.visibility());
        skill.setCreatedBy(scenario.creatorId());

        // Stub skill retrieval — skill exists in the user's org
        when(skillRepository.findByIdAndOrganizationId(scenario.skillId(), scenario.orgId()))
                .thenReturn(Optional.of(skill));

        // Stub team membership check
        UUID userId = scenario.userDetails().userId();
        if (scenario.isTeamMember()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(scenario.ownerTeamId());
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(scenario.ownerTeamId(), userId))
                    .thenReturn(Optional.of(member));
        } else {
            when(teamMemberRepository.findByTeamIdAndUserId(scenario.ownerTeamId(), userId))
                    .thenReturn(Optional.empty());
        }

        // Stub access grant check
        when(skillAccessGrantRepository.existsBySkillIdAndUserId(scenario.skillId(), userId))
                .thenReturn(scenario.hasAccessGrant());

        SkillServiceImpl service = new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillAccessGrantRepository,
                skillSlugGenerator, teamRepository, teamMemberRepository,
                skillTagRepository, taxonomyEntryRepository, auditService);

        return new TestFixture(service);
    }

    /**
     * Determines if access should be granted based on visibility rules.
     */
    private static boolean shouldHaveAccess(String visibility, Role role, boolean isCreator,
                                            boolean isTeamMember, boolean hasAccessGrant) {
        // Org_Admin always has access
        if (role == Role.Org_Admin) {
            return true;
        }

        return switch (visibility) {
            case "organization" -> true; // all users in org can see
            case "team" -> isCreator || isTeamMember;
            case "private" -> isCreator;
            case "restricted" -> isCreator || hasAccessGrant;
            default -> false;
        };
    }

    // --- Providers ---

    @Provide
    Arbitrary<VisibilityScenario> accessGrantedScenarios() {
        return allScenarios().filter(s -> shouldHaveAccess(
                s.visibility(), s.userRole(), s.isCreator(), s.isTeamMember(), s.hasAccessGrant()));
    }

    @Provide
    Arbitrary<VisibilityScenario> accessDeniedScenarios() {
        return allScenarios().filter(s -> !shouldHaveAccess(
                s.visibility(), s.userRole(), s.isCreator(), s.isTeamMember(), s.hasAccessGrant()));
    }

    @Provide
    Arbitrary<VisibilityScenario> orgAdminScenarios() {
        Arbitrary<String> visibilities = Arbitraries.of("private", "team", "organization", "restricted");

        return Combinators.combine(
                visibilities,
                Arbitraries.of(true, false),  // isTeamMember
                Arbitraries.of(true, false),  // isCreator
                Arbitraries.of(true, false)   // hasAccessGrant
        ).as((visibility, isTeamMember, isCreator, hasAccessGrant) -> {
            UUID orgId = UUID.randomUUID();
            UUID skillId = UUID.randomUUID();
            UUID ownerTeamId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID creatorId = isCreator ? userId : UUID.randomUUID();

            AlexandriaUserDetails userDetails = new AlexandriaUserDetails(
                    userId, orgId, Role.Org_Admin, "admin@example.com");

            return new VisibilityScenario(
                    skillId, visibility, Role.Org_Admin, isCreator, isTeamMember,
                    hasAccessGrant, userDetails, creatorId, ownerTeamId, orgId);
        });
    }

    private Arbitrary<VisibilityScenario> allScenarios() {
        Arbitrary<String> visibilities = Arbitraries.of("private", "team", "organization", "restricted");

        // Use non-admin roles only — Org_Admin is tested separately as it always has access
        Arbitrary<Role> roles = Arbitraries.of(
                Role.Read_Only_Auditor, Role.Skill_Consumer, Role.Skill_Author,
                Role.Reviewer, Role.Team_Owner, Role.Catalog_Admin);

        Arbitrary<Boolean> booleans = Arbitraries.of(true, false);

        return Combinators.combine(visibilities, roles, booleans, booleans, booleans)
                .as((visibility, role, isCreator, isTeamMember, hasAccessGrant) -> {
                    UUID orgId = UUID.randomUUID();
                    UUID skillId = UUID.randomUUID();
                    UUID ownerTeamId = UUID.randomUUID();
                    UUID userId = UUID.randomUUID();
                    UUID creatorId = isCreator ? userId : UUID.randomUUID();

                    AlexandriaUserDetails userDetails = new AlexandriaUserDetails(
                            userId, orgId, role, "user@example.com");

                    return new VisibilityScenario(
                            skillId, visibility, role, isCreator, isTeamMember,
                            hasAccessGrant, userDetails, creatorId, ownerTeamId, orgId);
                });
    }
}
