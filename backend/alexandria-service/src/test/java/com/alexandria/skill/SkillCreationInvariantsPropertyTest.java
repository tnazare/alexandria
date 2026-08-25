package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.entity.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for skill creation invariants in SkillServiceImpl.
 *
 * <p><b>Property 2: Skill creation invariants</b> —
 * for any valid creation request with different names and teams, the created Skill
 * SHALL have lifecycle_status="Draft", visibility="private", organization_id matching
 * the user's org, version_number=1, changelog="Initial draft", and created_by matching
 * the user.</p>
 *
 * <p><b>Validates: Requirements 1.1, 1.7, 1.9, 1.10</b></p>
 */
@Tag("skill-crud")
@Tag("Property-2-Skill-creation-invariants")
class SkillCreationInvariantsPropertyTest {

    /**
     * Property: Created skill always has lifecycle_status = "Draft".
     *
     * Validates: Requirements 1.1
     */
    @Property(tries = 100)
    void createdSkillHasDraftLifecycleStatus(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillEntity savedSkill = fixture.capturedSkill();
        assertThat(savedSkill.getLifecycleStatus()).isEqualTo("Draft");
    }

    /**
     * Property: Created skill always has visibility = "private".
     *
     * Validates: Requirements 1.9
     */
    @Property(tries = 100)
    void createdSkillHasPrivateVisibility(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillEntity savedSkill = fixture.capturedSkill();
        assertThat(savedSkill.getVisibility()).isEqualTo("private");
    }

    /**
     * Property: Created skill's organization_id matches the authenticated user's organization.
     *
     * Validates: Requirements 1.7
     */
    @Property(tries = 100)
    void createdSkillOrganizationIdMatchesUser(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillEntity savedSkill = fixture.capturedSkill();
        assertThat(savedSkill.getOrganizationId()).isEqualTo(input.userDetails().organizationId());
    }

    /**
     * Property: Initial skill version has version_number = 1.
     *
     * Validates: Requirements 1.1
     */
    @Property(tries = 100)
    void initialVersionHasVersionNumberOne(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillVersionEntity savedVersion = fixture.capturedVersion();
        assertThat(savedVersion.getVersionNumber()).isEqualTo(1);
    }

    /**
     * Property: Initial skill version has changelog = "Initial draft".
     *
     * Validates: Requirements 1.10
     */
    @Property(tries = 100)
    void initialVersionHasInitialDraftChangelog(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillVersionEntity savedVersion = fixture.capturedVersion();
        assertThat(savedVersion.getChangelog()).isEqualTo("Initial draft");
    }

    /**
     * Property: Created skill's created_by matches the authenticated user's userId.
     *
     * Validates: Requirements 1.1
     */
    @Property(tries = 100)
    void createdSkillCreatedByMatchesUser(@ForAll("validCreationInputs") CreationInput input) {
        var fixture = setupFixture(input);

        fixture.service.create(
                input.name(), input.description(), input.ownerTeamId(),
                input.canonicalInstructions(), input.userDetails());

        SkillEntity savedSkill = fixture.capturedSkill();
        assertThat(savedSkill.getCreatedBy()).isEqualTo(input.userDetails().userId());
    }

    // --- Test infrastructure ---

    record CreationInput(
            String name,
            String description,
            UUID ownerTeamId,
            String canonicalInstructions,
            AlexandriaUserDetails userDetails
    ) {}

    record TestFixture(
            SkillServiceImpl service,
            ArgumentCaptor<SkillEntity> skillCaptor,
            ArgumentCaptor<SkillVersionEntity> versionCaptor
    ) {
        SkillEntity capturedSkill() {
            // The first save captures the initial skill (before currentVersionId is set)
            return skillCaptor.getAllValues().get(0);
        }

        SkillVersionEntity capturedVersion() {
            return versionCaptor.getValue();
        }
    }

    private TestFixture setupFixture(CreationInput input) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        SkillVersionRepository skillVersionRepository = mock(SkillVersionRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);
        SkillSlugGenerator skillSlugGenerator = mock(SkillSlugGenerator.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillTagRepository skillTagRepository = mock(SkillTagRepository.class);
        com.alexandria.taxonomy.TaxonomyEntryRepository taxonomyEntryRepository = mock(com.alexandria.taxonomy.TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        UUID orgId = input.userDetails().organizationId();
        UUID userId = input.userDetails().userId();

        // Stub team exists in user's org
        TeamEntity team = new TeamEntity();
        team.setId(input.ownerTeamId());
        team.setOrganizationId(orgId);
        team.setName("Test Team");
        team.setSlug("test-team");
        team.setCreatedBy(userId);
        when(teamRepository.findByIdAndOrganizationId(input.ownerTeamId(), orgId))
                .thenReturn(Optional.of(team));

        // Stub user is a team member (for non-admin roles)
        if (!input.userDetails().role().isAdmin()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(input.ownerTeamId());
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(input.ownerTeamId(), userId))
                    .thenReturn(Optional.of(member));
        }

        // Stub slug generation
        when(skillSlugGenerator.generateUniqueSlug(eq(input.name()), eq(orgId), any()))
                .thenReturn(SkillSlugGenerator.generateSlug(input.name()));

        // Capture saved skill entity and return it with an ID
        ArgumentCaptor<SkillEntity> skillCaptor = ArgumentCaptor.forClass(SkillEntity.class);
        when(skillRepository.save(skillCaptor.capture())).thenAnswer(invocation -> {
            SkillEntity skill = invocation.getArgument(0);
            if (skill.getId() == null) {
                skill.setId(UUID.randomUUID());
            }
            return skill;
        });

        // Stub skill slug uniqueness check
        lenient().when(skillRepository.existsByOrganizationIdAndSlug(any(), any())).thenReturn(false);

        // Capture saved version entity and return it with an ID
        ArgumentCaptor<SkillVersionEntity> versionCaptor = ArgumentCaptor.forClass(SkillVersionEntity.class);
        when(skillVersionRepository.save(versionCaptor.capture())).thenAnswer(invocation -> {
            SkillVersionEntity version = invocation.getArgument(0);
            if (version.getId() == null) {
                version.setId(UUID.randomUUID());
            }
            return version;
        });

        SkillServiceImpl service = new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillAccessGrantRepository,
                skillSlugGenerator, teamRepository, teamMemberRepository,
                skillTagRepository, taxonomyEntryRepository, auditService);

        return new TestFixture(service, skillCaptor, versionCaptor);
    }

    // --- Providers ---

    @Provide
    Arbitrary<CreationInput> validCreationInputs() {
        Arbitrary<String> names = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_")
                .ofMinLength(3)
                .ofMaxLength(200)
                .filter(name -> name.chars().anyMatch(Character::isLetterOrDigit));

        Arbitrary<String> descriptions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?-")
                .ofMinLength(1)
                .ofMaxLength(200); // Keep short for test speed but still valid range

        Arbitrary<UUID> teamIds = Arbitraries.create(UUID::randomUUID);

        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 \n#*-_")
                .ofMinLength(1)
                .ofMaxLength(500); // Keep short for test speed but still valid range

        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@example.com"));

        return Combinators.combine(names, descriptions, teamIds, instructions, users)
                .as(CreationInput::new);
    }
}
