package com.alexandria.skill;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for versioning behavior and partial update preservation.
 *
 * <p><b>Property 6: Versioning behavior based on lifecycle state</b> —
 * Draft skills are edited in-place (same version, no new version created);
 * Published/Team_Approved/Company_Approved skills create a new version with
 * incremented number, revert lifecycle to Draft, and require changelog.</p>
 *
 * <p><b>Property 7: Partial update preserves omitted fields</b> —
 * When UpdateParams has null fields, those fields retain their previous values;
 * non-null fields are updated to the new values.</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.9</b></p>
 */
@Tag("skill-crud")
@Tag("Property-6-Versioning-behavior")
@Tag("Property-7-Partial-updates")
class SkillVersioningAndPartialUpdatePropertyTest {

    // ======================== Property 6: Versioning behavior ========================

    /**
     * Property 6a: Draft skill edits update in-place — no new version created,
     * version number remains unchanged.
     *
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    void draftSkillEditUpdatesInPlace(@ForAll("draftEditInputs") EditInput input) {
        var fixture = setupDraftFixture(input);

        fixture.service.update(input.skillId(), input.params(), input.userDetails());

        // Verify no new version was saved (only the existing version was saved once via update)
        ArgumentCaptor<SkillVersionEntity> versionCaptor = fixture.versionCaptor();
        List<SkillVersionEntity> savedVersions = versionCaptor.getAllValues();

        // All saves should be to the same version (in-place update)
        assertThat(savedVersions).allSatisfy(v ->
                assertThat(v.getVersionNumber()).isEqualTo(input.existingVersionNumber()));
    }

    /**
     * Property 6b: Draft skill edits keep the same version ID on the skill entity.
     *
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    void draftSkillEditKeepsSameVersionId(@ForAll("draftEditInputs") EditInput input) {
        var fixture = setupDraftFixture(input);

        SkillEntity result = fixture.service.update(input.skillId(), input.params(), input.userDetails());

        assertThat(result.getCurrentVersionId()).isEqualTo(input.existingVersionId());
    }

    /**
     * Property 6c: Published/Team_Approved/Company_Approved skill edits create a new version
     * with incremented version number.
     *
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    void publishedSkillEditCreatesNewVersionWithIncrementedNumber(
            @ForAll("publishedEditInputs") EditInput input) {
        var fixture = setupPublishedFixture(input);

        fixture.service.update(input.skillId(), input.params(), input.userDetails());

        List<SkillVersionEntity> savedVersions = fixture.versionCaptor().getAllValues();

        // In the published path, only the new version is saved
        assertThat(savedVersions).isNotEmpty();
        SkillVersionEntity newVersion = savedVersions.get(savedVersions.size() - 1);
        assertThat(newVersion.getVersionNumber()).isEqualTo(input.existingVersionCount() + 1);
    }

    /**
     * Property 6d: Published/Team_Approved/Company_Approved skill edits revert lifecycle to Draft.
     *
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    void publishedSkillEditRevertsLifecycleToDraft(
            @ForAll("publishedEditInputs") EditInput input) {
        var fixture = setupPublishedFixture(input);

        SkillEntity result = fixture.service.update(input.skillId(), input.params(), input.userDetails());

        assertThat(result.getLifecycleStatus()).isEqualTo("Draft");
    }

    /**
     * Property 6e: Published skill edits store the changelog on the new version.
     *
     * Validates: Requirements 3.4
     */
    @Property(tries = 100)
    void publishedSkillEditStoresChangelogOnNewVersion(
            @ForAll("publishedEditInputs") EditInput input) {
        var fixture = setupPublishedFixture(input);

        fixture.service.update(input.skillId(), input.params(), input.userDetails());

        List<SkillVersionEntity> savedVersions = fixture.versionCaptor().getAllValues();

        // In the published path, only the new version is saved
        assertThat(savedVersions).isNotEmpty();
        SkillVersionEntity newVersion = savedVersions.get(savedVersions.size() - 1);
        assertThat(newVersion.getChangelog()).isEqualTo(input.params().changelog());
    }

    // ======================== Property 7: Partial update preserves omitted fields ========================

    /**
     * Property 7a: Null fields in UpdateParams retain their previous values after update (Draft).
     *
     * Validates: Requirements 3.9
     */
    @Property(tries = 100)
    void nullFieldsRetainPreviousValuesForDraftSkill(
            @ForAll("partialUpdateInputs") PartialUpdateInput input) {
        var fixture = setupPartialUpdateFixture(input);

        fixture.service.update(input.skillId(), input.params(), input.userDetails());

        ArgumentCaptor<SkillVersionEntity> versionCaptor = fixture.versionCaptor();
        SkillVersionEntity savedVersion = versionCaptor.getValue();

        // Verify null fields retain original values
        if (input.params().canonicalInstructions() == null) {
            assertThat(savedVersion.getCanonicalInstructions())
                    .isEqualTo(input.originalVersion().getCanonicalInstructions());
        }
        if (input.params().applicability() == null) {
            assertThat(savedVersion.getApplicability())
                    .isEqualTo(input.originalVersion().getApplicability());
        }
        if (input.params().limitations() == null) {
            assertThat(savedVersion.getLimitations())
                    .isEqualTo(input.originalVersion().getLimitations());
        }
        if (input.params().whenToUse() == null) {
            assertThat(savedVersion.getWhenToUse())
                    .isEqualTo(input.originalVersion().getWhenToUse());
        }
        if (input.params().examples() == null) {
            assertThat(savedVersion.getExamples())
                    .isEqualTo(input.originalVersion().getExamples());
        }
        if (input.params().supportedTools() == null) {
            assertThat(savedVersion.getSupportedTools())
                    .isEqualTo(input.originalVersion().getSupportedTools());
        }
    }

    /**
     * Property 7b: Non-null fields in UpdateParams are updated to the new values (Draft).
     *
     * Validates: Requirements 3.9
     */
    @Property(tries = 100)
    void nonNullFieldsAreUpdatedForDraftSkill(
            @ForAll("partialUpdateInputs") PartialUpdateInput input) {
        var fixture = setupPartialUpdateFixture(input);

        fixture.service.update(input.skillId(), input.params(), input.userDetails());

        ArgumentCaptor<SkillVersionEntity> versionCaptor = fixture.versionCaptor();
        SkillVersionEntity savedVersion = versionCaptor.getValue();

        // Verify non-null fields are updated
        if (input.params().canonicalInstructions() != null) {
            assertThat(savedVersion.getCanonicalInstructions())
                    .isEqualTo(input.params().canonicalInstructions());
        }
        if (input.params().applicability() != null) {
            assertThat(savedVersion.getApplicability())
                    .isEqualTo(input.params().applicability());
        }
        if (input.params().limitations() != null) {
            assertThat(savedVersion.getLimitations())
                    .isEqualTo(input.params().limitations());
        }
        if (input.params().whenToUse() != null) {
            assertThat(savedVersion.getWhenToUse())
                    .isEqualTo(input.params().whenToUse());
        }
        if (input.params().examples() != null) {
            assertThat(savedVersion.getExamples())
                    .isEqualTo(input.params().examples());
        }
        if (input.params().supportedTools() != null) {
            assertThat(savedVersion.getSupportedTools())
                    .isEqualTo(input.params().supportedTools());
        }
    }

    /**
     * Property 7c: Skill-level null fields (name, description, riskLevel) retain previous values.
     *
     * Validates: Requirements 3.9
     */
    @Property(tries = 100)
    void nullSkillLevelFieldsRetainPreviousValues(
            @ForAll("partialUpdateInputs") PartialUpdateInput input) {
        var fixture = setupPartialUpdateFixture(input);

        SkillEntity result = fixture.service.update(input.skillId(), input.params(), input.userDetails());

        if (input.params().name() == null) {
            assertThat(result.getName()).isEqualTo(input.originalSkillName());
        }
        if (input.params().description() == null) {
            assertThat(result.getDescription()).isEqualTo(input.originalSkillDescription());
        }
        if (input.params().riskLevel() == null) {
            assertThat(result.getRiskLevel()).isEqualTo(input.originalSkillRiskLevel());
        }
    }

    /**
     * Property 7d: Non-null skill-level fields are updated to the new values.
     *
     * Validates: Requirements 3.9
     */
    @Property(tries = 100)
    void nonNullSkillLevelFieldsAreUpdated(
            @ForAll("partialUpdateInputs") PartialUpdateInput input) {
        var fixture = setupPartialUpdateFixture(input);

        SkillEntity result = fixture.service.update(input.skillId(), input.params(), input.userDetails());

        if (input.params().name() != null) {
            assertThat(result.getName()).isEqualTo(input.params().name());
        }
        if (input.params().description() != null) {
            assertThat(result.getDescription()).isEqualTo(input.params().description());
        }
        if (input.params().riskLevel() != null) {
            assertThat(result.getRiskLevel()).isEqualTo(input.params().riskLevel());
        }
    }

    // ======================== Test infrastructure ========================

    record EditInput(
            UUID skillId,
            UUID existingVersionId,
            int existingVersionNumber,
            int existingVersionCount,
            String lifecycleStatus,
            UpdateParams params,
            AlexandriaUserDetails userDetails
    ) {}

    record PartialUpdateInput(
            UUID skillId,
            String originalSkillName,
            String originalSkillDescription,
            String originalSkillRiskLevel,
            SkillVersionEntity originalVersion,
            UpdateParams params,
            AlexandriaUserDetails userDetails
    ) {}

    record TestFixture(
            SkillServiceImpl service,
            SkillVersionRepository skillVersionRepository
    ) {
        ArgumentCaptor<SkillVersionEntity> versionCaptor() {
            ArgumentCaptor<SkillVersionEntity> captor = ArgumentCaptor.forClass(SkillVersionEntity.class);
            verify(skillVersionRepository).save(captor.capture());
            return captor;
        }
    }

    record MultiSaveTestFixture(
            SkillServiceImpl service,
            ArgumentCaptor<SkillVersionEntity> versionCaptor
    ) {}

    private MultiSaveTestFixture setupDraftFixture(EditInput input) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        SkillVersionRepository skillVersionRepository = mock(SkillVersionRepository.class);
        SkillVariantRepository skillVariantRepository = mock(SkillVariantRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);
        SkillSlugGenerator skillSlugGenerator = mock(SkillSlugGenerator.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillTagRepository skillTagRepository = mock(SkillTagRepository.class);
        TaxonomyEntryRepository taxonomyEntryRepository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        UUID orgId = input.userDetails().organizationId();
        UUID userId = input.userDetails().userId();

        // Stub skill entity
        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(UUID.randomUUID());
        skill.setName("Original Name");
        skill.setSlug("original-name");
        skill.setDescription("Original description");
        skill.setLifecycleStatus("Draft");
        skill.setVisibility("private");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(input.existingVersionId());

        when(skillRepository.findByIdAndOrganizationId(input.skillId(), orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership for non-admin
        if (!input.userDetails().role().isAdmin()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(skill.getOwnerTeamId());
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId))
                    .thenReturn(Optional.of(member));
        }

        // Stub existing version
        SkillVersionEntity existingVersion = new SkillVersionEntity();
        existingVersion.setId(input.existingVersionId());
        existingVersion.setSkillId(input.skillId());
        existingVersion.setVersionNumber(input.existingVersionNumber());
        existingVersion.setCanonicalInstructions("existing instructions");
        existingVersion.setApplicability("existing applicability");
        existingVersion.setLimitations("existing limitations");
        existingVersion.setWhenToUse("existing when to use");
        existingVersion.setExamples("existing examples");
        existingVersion.setSupportedTools(List.of("github_copilot"));
        existingVersion.setChangelog("existing changelog");

        when(skillVersionRepository.findById(input.existingVersionId()))
                .thenReturn(Optional.of(existingVersion));

        // Stub slug generation
        lenient().when(skillSlugGenerator.generateUniqueSlug(any(), eq(orgId), any()))
                .thenAnswer(inv -> inv.getArgument(0).toString().toLowerCase().replaceAll("\\s+", "-"));

        // Capture version saves
        ArgumentCaptor<SkillVersionEntity> versionCaptor = ArgumentCaptor.forClass(SkillVersionEntity.class);
        when(skillVersionRepository.save(versionCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Capture skill saves
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillServiceImpl service = new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);

        return new MultiSaveTestFixture(service, versionCaptor);
    }

    private MultiSaveTestFixture setupPublishedFixture(EditInput input) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        SkillVersionRepository skillVersionRepository = mock(SkillVersionRepository.class);
        SkillVariantRepository skillVariantRepository = mock(SkillVariantRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);
        SkillSlugGenerator skillSlugGenerator = mock(SkillSlugGenerator.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillTagRepository skillTagRepository = mock(SkillTagRepository.class);
        TaxonomyEntryRepository taxonomyEntryRepository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        UUID orgId = input.userDetails().organizationId();
        UUID userId = input.userDetails().userId();

        // Stub skill entity
        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(UUID.randomUUID());
        skill.setName("Published Skill");
        skill.setSlug("published-skill");
        skill.setDescription("A published skill");
        skill.setLifecycleStatus(input.lifecycleStatus());
        skill.setVisibility("organization");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(input.existingVersionId());

        when(skillRepository.findByIdAndOrganizationId(input.skillId(), orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership for non-admin
        if (!input.userDetails().role().isAdmin()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(skill.getOwnerTeamId());
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId))
                    .thenReturn(Optional.of(member));
        }

        // Stub existing version
        SkillVersionEntity existingVersion = new SkillVersionEntity();
        existingVersion.setId(input.existingVersionId());
        existingVersion.setSkillId(input.skillId());
        existingVersion.setVersionNumber(input.existingVersionNumber());
        existingVersion.setCanonicalInstructions("published instructions");
        existingVersion.setApplicability("published applicability");
        existingVersion.setLimitations("published limitations");
        existingVersion.setWhenToUse("published when to use");
        existingVersion.setExamples("published examples");
        existingVersion.setSupportedTools(List.of("cursor", "aws_kiro"));
        existingVersion.setChangelog("published changelog");

        when(skillVersionRepository.findById(input.existingVersionId()))
                .thenReturn(Optional.of(existingVersion));

        // Stub version count
        when(skillVersionRepository.countBySkillId(input.skillId()))
                .thenReturn(input.existingVersionCount());

        // Stub slug generation
        lenient().when(skillSlugGenerator.generateUniqueSlug(any(), eq(orgId), any()))
                .thenAnswer(inv -> inv.getArgument(0).toString().toLowerCase().replaceAll("\\s+", "-"));

        // Capture version saves
        ArgumentCaptor<SkillVersionEntity> versionCaptor = ArgumentCaptor.forClass(SkillVersionEntity.class);
        when(skillVersionRepository.save(versionCaptor.capture())).thenAnswer(inv -> {
            SkillVersionEntity v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(UUID.randomUUID());
            }
            return v;
        });

        // Capture skill saves
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillServiceImpl service = new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);

        return new MultiSaveTestFixture(service, versionCaptor);
    }

    private TestFixture setupPartialUpdateFixture(PartialUpdateInput input) {
        SkillRepository skillRepository = mock(SkillRepository.class);
        SkillVersionRepository skillVersionRepository = mock(SkillVersionRepository.class);
        SkillVariantRepository skillVariantRepository = mock(SkillVariantRepository.class);
        SkillAccessGrantRepository skillAccessGrantRepository = mock(SkillAccessGrantRepository.class);
        SkillSlugGenerator skillSlugGenerator = mock(SkillSlugGenerator.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        SkillTagRepository skillTagRepository = mock(SkillTagRepository.class);
        TaxonomyEntryRepository taxonomyEntryRepository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        UUID orgId = input.userDetails().organizationId();
        UUID userId = input.userDetails().userId();
        UUID versionId = input.originalVersion().getId();

        // Stub skill entity with original values
        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(UUID.randomUUID());
        skill.setName(input.originalSkillName());
        skill.setSlug(input.originalSkillName().toLowerCase().replaceAll("\\s+", "-"));
        skill.setDescription(input.originalSkillDescription());
        skill.setLifecycleStatus("Draft");
        skill.setVisibility("private");
        skill.setRiskLevel(input.originalSkillRiskLevel());
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);

        when(skillRepository.findByIdAndOrganizationId(input.skillId(), orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership for non-admin
        if (!input.userDetails().role().isAdmin()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(skill.getOwnerTeamId());
            member.setUserId(userId);
            member.setTeamRole("member");
            when(teamMemberRepository.findByTeamIdAndUserId(skill.getOwnerTeamId(), userId))
                    .thenReturn(Optional.of(member));
        }

        // Stub existing version with original values
        when(skillVersionRepository.findById(versionId))
                .thenReturn(Optional.of(input.originalVersion()));

        // Stub slug generation
        lenient().when(skillSlugGenerator.generateUniqueSlug(any(), eq(orgId), any()))
                .thenAnswer(inv -> inv.getArgument(0).toString().toLowerCase().replaceAll("\\s+", "-"));

        // Capture version saves
        when(skillVersionRepository.save(any(SkillVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Capture skill saves
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillServiceImpl service = new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);

        return new TestFixture(service, skillVersionRepository);
    }

    // ======================== Providers ========================

    @Provide
    Arbitrary<EditInput> draftEditInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> versionIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Integer> versionNumbers = Arbitraries.integers().between(1, 10);

        Arbitrary<UpdateParams> params = draftUpdateParams();

        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@example.com"));

        return Combinators.combine(skillIds, versionIds, versionNumbers, params, users)
                .as((skillId, versionId, versionNum, p, user) ->
                        new EditInput(skillId, versionId, versionNum, versionNum, "Draft", p, user));
    }

    @Provide
    Arbitrary<EditInput> publishedEditInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> versionIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Integer> versionNumbers = Arbitraries.integers().between(1, 10);
        Arbitrary<String> statuses = Arbitraries.of("Published", "Team_Approved", "Company_Approved");

        Arbitrary<UpdateParams> params = publishedUpdateParams();

        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@example.com"));

        return Combinators.combine(skillIds, versionIds, versionNumbers, statuses, params, users)
                .as((skillId, versionId, versionNum, status, p, user) ->
                        // existingVersionCount must be >= existingVersionNumber
                        new EditInput(skillId, versionId, versionNum, versionNum, status, p, user));
    }

    @Provide
    Arbitrary<PartialUpdateInput> partialUpdateInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);

        Arbitrary<String> originalNames = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(3).ofMaxLength(50);

        Arbitrary<String> originalDescriptions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100);

        Arbitrary<String> originalRiskLevels = Arbitraries.of("low", "medium", "high", "restricted");

        Arbitrary<SkillVersionEntity> originalVersions = createOriginalVersionArbitrary();

        Arbitrary<UpdateParams> params = partialUpdateParamsArbitrary();

        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@example.com"));

        return Combinators.combine(skillIds, originalNames, originalDescriptions, originalRiskLevels, originalVersions, params, users)
                .as(PartialUpdateInput::new);
    }

    // ======================== Helper Arbitraries ========================

    private Arbitrary<UpdateParams> draftUpdateParams() {
        Arbitrary<String> names = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(3).ofMaxLength(50)
                .injectNull(0.5);

        Arbitrary<String> descriptions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789 \n#*-_")
                .ofMinLength(1).ofMaxLength(200)
                .injectNull(0.5);

        Arbitrary<String> applicabilities = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> limitations = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> whenToUse = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> examples = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> changelogs = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> riskLevels = Arbitraries.of("low", "medium", "high", "restricted")
                .injectNull(0.5);

        // Combine first 8 fields, then flatMap to add the 9th
        return Combinators.combine(names, descriptions, instructions, applicabilities,
                        limitations, whenToUse, examples, changelogs)
                .flatAs((name, desc, instr, applic, limit, when, ex, cl) ->
                        riskLevels.map(risk ->
                                new UpdateParams(name, desc, instr, applic, limit, when, ex, cl, null, null, risk)));
    }

    private Arbitrary<UpdateParams> publishedUpdateParams() {
        // For published edits, changelog is required (non-null, non-blank, 1-2000 chars)
        Arbitrary<String> changelogs = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz")
                .ofMinLength(1).ofMaxLength(50)
                .map(s -> "change: " + s); // Ensure always non-blank

        Arbitrary<String> names = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(3).ofMaxLength(50)
                .injectNull(0.5);

        Arbitrary<String> descriptions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789 \n#*-_")
                .ofMinLength(1).ofMaxLength(200)
                .injectNull(0.5);

        Arbitrary<String> applicabilities = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> riskLevels = Arbitraries.of("low", "medium", "high", "restricted")
                .injectNull(0.5);

        return Combinators.combine(changelogs, names, descriptions, instructions, applicabilities, riskLevels)
                .as((cl, name, desc, instr, applic, risk) ->
                        new UpdateParams(name, desc, instr, applic, null, null, null, cl, null, null, risk));
    }

    private Arbitrary<SkillVersionEntity> createOriginalVersionArbitrary() {
        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789 ")
                .ofMinLength(1).ofMaxLength(200);

        Arbitrary<String> applicabilities = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100);

        Arbitrary<String> limitations = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100);

        Arbitrary<String> whenToUse = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100);

        Arbitrary<String> examples = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100);

        Arbitrary<List<String>> tools = Arbitraries.of("github_copilot", "cursor", "aws_kiro", "codex")
                .list().ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(instructions, applicabilities, limitations, whenToUse, examples, tools)
                .as((instr, applic, limit, when, ex, t) -> {
                    SkillVersionEntity version = new SkillVersionEntity();
                    version.setId(UUID.randomUUID());
                    version.setSkillId(UUID.randomUUID());
                    version.setVersionNumber(1);
                    version.setCanonicalInstructions(instr);
                    version.setApplicability(applic);
                    version.setLimitations(limit);
                    version.setWhenToUse(when);
                    version.setExamples(ex);
                    version.setSupportedTools(new ArrayList<>(t));
                    version.setChangelog("original changelog");
                    return version;
                });
    }

    private Arbitrary<UpdateParams> partialUpdateParamsArbitrary() {
        // Generate UpdateParams where some fields are null and some are non-null
        Arbitrary<String> names = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(3).ofMaxLength(50)
                .injectNull(0.5);

        Arbitrary<String> descriptions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789 \n#*-_")
                .ofMinLength(1).ofMaxLength(200)
                .injectNull(0.5);

        Arbitrary<String> applicabilities = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> limitations = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> whenToUse = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> examples = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<String> changelogs = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(1).ofMaxLength(100)
                .injectNull(0.5);

        Arbitrary<List<String>> supportedTools = Arbitraries.of("github_copilot", "cursor", "aws_kiro")
                .list().ofMinSize(0).ofMaxSize(3)
                .injectNull(0.5);

        Arbitrary<String> riskLevels = Arbitraries.of("low", "medium", "high", "restricted")
                .injectNull(0.5);

        // Combine first 8 fields, then flatMap to add the rest
        return Combinators.combine(names, descriptions, instructions, applicabilities,
                        limitations, whenToUse, examples, changelogs)
                .flatAs((name, desc, instr, applic, limit, when, ex, cl) ->
                        Combinators.combine(supportedTools, riskLevels)
                                .as((tools, risk) ->
                                        new UpdateParams(name, desc, instr, applic, limit, when, ex, cl, null, tools, risk)));
    }
}
