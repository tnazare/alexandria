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
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.entity.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for lifecycle state machine constraints and publish validation completeness.
 *
 * <p><b>Property 8: Lifecycle state machine constraints</b> —
 * Publish only succeeds when status=Draft (409 otherwise); delete only succeeds when
 * status=Draft (409 otherwise); edit rejected with 409 when status is Deprecated/Archived/Blocked.</p>
 *
 * <p><b>Property 9: Publish validation completeness</b> —
 * When any required field is missing, publish throws PublishValidationException listing exactly
 * the missing fields (no false positives, no false negatives). When all fields present, publish succeeds.</p>
 *
 * <p><b>Validates: Requirements 3.6, 4.1, 4.2, 4.4, 8.2</b></p>
 */
@Tag("skill-crud")
@Tag("Property-8-Lifecycle-state-machine")
@Tag("Property-9-Publish-validation")
class SkillLifecycleAndPublishValidationPropertyTest {

    // All lifecycle states
    private static final List<String> ALL_STATES = List.of(
            "Draft", "Published", "Team_Approved", "Company_Approved",
            "Deprecated", "Archived", "Blocked");

    private static final List<String> NON_DRAFT_STATES = List.of(
            "Published", "Team_Approved", "Company_Approved",
            "Deprecated", "Archived", "Blocked");

    private static final List<String> FROZEN_STATES = List.of(
            "Deprecated", "Archived", "Blocked");

    // ======================== Property 8: Lifecycle state machine constraints ========================

    /**
     * Property 8a: Publish only succeeds when lifecycle status is Draft.
     * Any other state results in SkillConflictException (409).
     *
     * Validates: Requirements 4.4
     */
    @Property(tries = 100)
    void publishRejectsNonDraftWithConflict(@ForAll("nonDraftPublishInputs") LifecycleInput input) {
        var service = setupLifecycleFixture(input);

        assertThatThrownBy(() -> service.publish(input.skillId(), input.userDetails()))
                .isInstanceOf(SkillConflictException.class)
                .hasMessageContaining("Draft");
    }

    /**
     * Property 8b: Edit (update) is rejected with 409 when lifecycle status is
     * Deprecated, Archived, or Blocked.
     *
     * Validates: Requirements 3.6
     */
    @Property(tries = 100)
    void editRejectsFrozenStatesWithConflict(@ForAll("frozenStateEditInputs") LifecycleInput input) {
        var service = setupLifecycleFixture(input);

        UpdateParams params = new UpdateParams(
                "New Name", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(input.skillId(), params, input.userDetails()))
                .isInstanceOf(SkillConflictException.class)
                .hasMessageContaining(input.lifecycleStatus());
    }

    /**
     * Property 8c: Publish succeeds when lifecycle status is Draft and all metadata is valid.
     * The resulting skill has lifecycle status "Published".
     *
     * Validates: Requirements 4.4
     */
    @Property(tries = 100)
    void publishSucceedsForDraftWithCompleteMetadata(@ForAll("draftPublishInputs") PublishInput input) {
        var service = setupPublishFixture(input, true);

        SkillEntity result = service.publish(input.skillId(), input.userDetails());

        assertThat(result.getLifecycleStatus()).isEqualTo("Published");
    }

    /**
     * Property 8d: Edit succeeds for Draft status (no conflict thrown).
     *
     * Validates: Requirements 3.6
     */
    @Property(tries = 100)
    void editSucceedsForDraftStatus(@ForAll("draftEditInputs") LifecycleInput input) {
        var service = setupDraftEditFixture(input);

        UpdateParams params = new UpdateParams(
                null, null, "updated instructions", null, null, null, null, null, null, null, null);

        // Should NOT throw SkillConflictException
        SkillEntity result = service.update(input.skillId(), params, input.userDetails());
        assertThat(result).isNotNull();
    }

    // ======================== Property 9: Publish validation completeness ========================

    /**
     * Property 9a: When any required field is missing, publish throws PublishValidationException
     * listing exactly the missing fields — no false positives, no false negatives.
     *
     * Validates: Requirements 4.1, 4.2
     */
    @Property(tries = 100)
    void publishReportsExactlyMissingFields(@ForAll("incompletePublishInputs") MetadataInput input) {
        var service = setupMetadataFixture(input);

        assertThatThrownBy(() -> service.publish(input.skillId(), input.userDetails()))
                .isInstanceOf(PublishValidationException.class)
                .satisfies(thrown -> {
                    PublishValidationException ex = (PublishValidationException) thrown;
                    List<String> reported = ex.getMissingFields();

                    // Verify no false negatives: every actually missing field is reported
                    for (String expectedMissing : input.expectedMissingFields()) {
                        assertThat(reported).contains(expectedMissing);
                    }

                    // Verify no false positives: every reported field is actually missing
                    for (String reportedField : reported) {
                        assertThat(input.expectedMissingFields()).contains(reportedField);
                    }

                    // Sizes must match (belt-and-suspenders)
                    assertThat(reported).hasSameSizeAs(input.expectedMissingFields());
                });
    }

    /**
     * Property 9b: When all required fields are present, publish succeeds (no exception thrown).
     *
     * Validates: Requirements 4.1
     */
    @Property(tries = 100)
    void publishSucceedsWhenAllMetadataPresent(@ForAll("completePublishInputs") MetadataInput input) {
        var service = setupMetadataFixture(input);

        SkillEntity result = service.publish(input.skillId(), input.userDetails());

        assertThat(result.getLifecycleStatus()).isEqualTo("Published");
    }

    // ======================== Test records ========================

    record LifecycleInput(
            UUID skillId,
            String lifecycleStatus,
            AlexandriaUserDetails userDetails
    ) {}

    record PublishInput(
            UUID skillId,
            AlexandriaUserDetails userDetails
    ) {}

    record MetadataInput(
            UUID skillId,
            boolean hasTaskTypeTag,
            boolean hasVisibility,
            boolean hasSupportedTools,
            boolean hasRiskLevel,
            boolean hasApplicability,
            boolean hasExamples,
            List<String> expectedMissingFields,
            AlexandriaUserDetails userDetails
    ) {}

    // ======================== Fixture setup methods ========================

    private SkillServiceImpl setupLifecycleFixture(LifecycleInput input) {
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
        UUID teamId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Test Skill");
        skill.setSlug("test-skill");
        skill.setDescription("Test description");
        skill.setLifecycleStatus(input.lifecycleStatus());
        skill.setVisibility("organization");
        skill.setRiskLevel("medium");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);

        when(skillRepository.findByIdAndOrganizationId(input.skillId(), orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("member");
        when(teamMemberRepository.findByTeamIdAndUserId(teamId, userId))
                .thenReturn(Optional.of(member));

        // Stub version
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setSkillId(input.skillId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions("instructions");
        version.setSupportedTools(List.of("github_copilot"));
        version.setApplicability("applicable");
        version.setExamples("example");

        when(skillVersionRepository.findById(versionId)).thenReturn(Optional.of(version));

        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(skillVersionRepository.save(any(SkillVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        return new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);
    }

    private SkillServiceImpl setupDraftEditFixture(LifecycleInput input) {
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
        UUID teamId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Draft Skill");
        skill.setSlug("draft-skill");
        skill.setDescription("Draft description");
        skill.setLifecycleStatus("Draft");
        skill.setVisibility("private");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);

        when(skillRepository.findByIdAndOrganizationId(input.skillId(), orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("member");
        when(teamMemberRepository.findByTeamIdAndUserId(teamId, userId))
                .thenReturn(Optional.of(member));

        // Stub version
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setSkillId(input.skillId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions("instructions");
        version.setSupportedTools(List.of("github_copilot"));
        version.setApplicability("applicable");
        version.setExamples("example");

        when(skillVersionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillVersionRepository.save(any(SkillVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        return new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);
    }

    private SkillServiceImpl setupPublishFixture(PublishInput input, boolean completeMetadata) {
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
        UUID teamId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID skillId = input.skillId();

        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Publishable Skill");
        skill.setSlug("publishable-skill");
        skill.setDescription("A skill ready for publishing");
        skill.setLifecycleStatus("Draft");
        skill.setVisibility("organization");
        skill.setRiskLevel("medium");
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);

        when(skillRepository.findByIdAndOrganizationId(skillId, orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("member");
        when(teamMemberRepository.findByTeamIdAndUserId(teamId, userId))
                .thenReturn(Optional.of(member));

        // Stub version with complete metadata
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setSkillId(skillId);
        version.setVersionNumber(1);
        version.setCanonicalInstructions("# Complete instructions");
        version.setSupportedTools(List.of("github_copilot", "cursor"));
        version.setApplicability("Applies to all code review tasks");
        version.setExamples("Example: Run code review on PR");

        when(skillVersionRepository.findById(versionId)).thenReturn(Optional.of(version));

        // Stub task_type tag
        UUID tagId = UUID.randomUUID();
        UUID taxonomyEntryId = UUID.randomUUID();
        SkillTagEntity tagEntity = new SkillTagEntity();
        tagEntity.setId(tagId);
        tagEntity.setSkillId(skillId);
        tagEntity.setTaxonomyEntryId(taxonomyEntryId);

        when(skillTagRepository.findBySkillId(skillId)).thenReturn(List.of(tagEntity));

        TaxonomyEntryEntity taxonomyEntry = new TaxonomyEntryEntity();
        taxonomyEntry.setId(taxonomyEntryId);
        taxonomyEntry.setTagType("task_type");
        taxonomyEntry.setValue("code-review");
        taxonomyEntry.setActive(true);

        when(taxonomyEntryRepository.findAllById(List.of(taxonomyEntryId)))
                .thenReturn(List.of(taxonomyEntry));

        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillVersionRepository.save(any(SkillVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        return new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);
    }

    private SkillServiceImpl setupMetadataFixture(MetadataInput input) {
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
        UUID teamId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID skillId = input.skillId();

        // Build skill entity with conditional metadata
        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(teamId);
        skill.setName("Metadata Test Skill");
        skill.setSlug("metadata-test-skill");
        skill.setDescription("Testing metadata validation");
        skill.setLifecycleStatus("Draft");
        skill.setVisibility(input.hasVisibility() ? "organization" : null);
        skill.setRiskLevel(input.hasRiskLevel() ? "medium" : null);
        skill.setCreatedBy(userId);
        skill.setCurrentVersionId(versionId);

        when(skillRepository.findByIdAndOrganizationId(skillId, orgId))
                .thenReturn(Optional.of(skill));

        // Stub team membership
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("member");
        when(teamMemberRepository.findByTeamIdAndUserId(teamId, userId))
                .thenReturn(Optional.of(member));

        // Stub version with conditional fields
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setSkillId(skillId);
        version.setVersionNumber(1);
        version.setCanonicalInstructions("# Instructions");
        version.setSupportedTools(input.hasSupportedTools() ? List.of("github_copilot") : List.of());
        version.setApplicability(input.hasApplicability() ? "Applies to X" : null);
        version.setExamples(input.hasExamples() ? "Example content" : null);

        when(skillVersionRepository.findById(versionId)).thenReturn(Optional.of(version));

        // Stub tags based on hasTaskTypeTag
        if (input.hasTaskTypeTag()) {
            UUID taxonomyEntryId = UUID.randomUUID();
            SkillTagEntity tagEntity = new SkillTagEntity();
            tagEntity.setId(UUID.randomUUID());
            tagEntity.setSkillId(skillId);
            tagEntity.setTaxonomyEntryId(taxonomyEntryId);

            when(skillTagRepository.findBySkillId(skillId)).thenReturn(List.of(tagEntity));

            TaxonomyEntryEntity taxonomyEntry = new TaxonomyEntryEntity();
            taxonomyEntry.setId(taxonomyEntryId);
            taxonomyEntry.setTagType("task_type");
            taxonomyEntry.setValue("code-review");
            taxonomyEntry.setActive(true);

            when(taxonomyEntryRepository.findAllById(List.of(taxonomyEntryId)))
                    .thenReturn(List.of(taxonomyEntry));
        } else {
            when(skillTagRepository.findBySkillId(skillId)).thenReturn(List.of());
        }

        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillVersionRepository.save(any(SkillVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        return new SkillServiceImpl(
                skillRepository, skillVersionRepository, skillVariantRepository,
                skillAccessGrantRepository, skillSlugGenerator, teamRepository,
                teamMemberRepository, skillTagRepository, taxonomyEntryRepository, auditService);
    }

    // ======================== Providers ========================

    @Provide
    Arbitrary<LifecycleInput> nonDraftPublishInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> statuses = Arbitraries.of(NON_DRAFT_STATES.toArray(String[]::new));
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        return Combinators.combine(skillIds, statuses, users)
                .as(LifecycleInput::new);
    }

    @Provide
    Arbitrary<LifecycleInput> frozenStateEditInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> statuses = Arbitraries.of(FROZEN_STATES.toArray(String[]::new));
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        return Combinators.combine(skillIds, statuses, users)
                .as(LifecycleInput::new);
    }

    @Provide
    Arbitrary<LifecycleInput> draftEditInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> statuses = Arbitraries.just("Draft");
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        return Combinators.combine(skillIds, statuses, users)
                .as(LifecycleInput::new);
    }

    @Provide
    Arbitrary<PublishInput> draftPublishInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        return Combinators.combine(skillIds, users)
                .as(PublishInput::new);
    }

    @Provide
    Arbitrary<MetadataInput> incompletePublishInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        // Generate random boolean combinations for each metadata field,
        // ensuring at least one field is missing (not all true)
        Arbitrary<Boolean> booleans = Arbitraries.of(true, false);

        return Combinators.combine(skillIds, booleans, booleans, booleans, booleans, booleans, booleans, users)
                .as((skillId, hasTag, hasVis, hasTools, hasRisk, hasApplic, hasExamples, user) -> {
                    // Compute expected missing fields
                    List<String> missing = new ArrayList<>();
                    if (!hasTag) missing.add("task_type_tag");
                    if (!hasVis) missing.add("visibility");
                    if (!hasTools) missing.add("supportedTools");
                    if (!hasRisk) missing.add("riskLevel");
                    if (!hasApplic) missing.add("applicability");
                    if (!hasExamples) missing.add("examples");

                    return new MetadataInput(skillId, hasTag, hasVis, hasTools, hasRisk, hasApplic, hasExamples, missing, user);
                })
                .filter(input -> !input.expectedMissingFields().isEmpty()); // Ensure at least one is missing
    }

    @Provide
    Arbitrary<MetadataInput> completePublishInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<AlexandriaUserDetails> users = userDetailsArbitrary();

        // All metadata fields present
        return Combinators.combine(skillIds, users)
                .as((skillId, user) -> new MetadataInput(
                        skillId, true, true, true, true, true, true, List.of(), user));
    }

    // ======================== Helper Arbitraries ========================

    private Arbitrary<AlexandriaUserDetails> userDetailsArbitrary() {
        return Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@example.com"));
    }
}
