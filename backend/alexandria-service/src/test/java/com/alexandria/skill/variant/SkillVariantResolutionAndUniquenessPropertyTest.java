package com.alexandria.skill.variant;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillConflictException;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.SkillVariantRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVariantEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for skill variant resolution and uniqueness enforcement.
 *
 * <p><b>Property 10: Variant resolution with fallback</b> —
 * For a requested tool: if a variant exists for that tool on the version, return its instructions;
 * otherwise return null (caller uses canonical instructions as fallback).</p>
 *
 * <p><b>Property 11: Unique variant per tool per version</b> —
 * Attempting to create a variant when one already exists for the same tool on the same version
 * SHALL throw SkillConflictException.</p>
 *
 * <p><b>Validates: Requirements 6.4, 6.5, 6.6</b></p>
 */
@Tag("skill-crud")
@Tag("Property-10-Variant-resolution-with-fallback")
@Tag("Property-11-Unique-variant-per-tool-per-version")
class SkillVariantResolutionAndUniquenessPropertyTest {

    private static final List<String> SUPPORTED_TOOLS = List.of(
            "generic", "github_copilot", "codex", "cursor", "aws_kiro",
            "sourcegraph_cody", "claude_code", "jetbrains_ai", "internal_agent"
    );

    // ===== Property 10: Variant resolution with fallback =====

    /**
     * Property 10: When a variant exists for the requested tool, resolve() returns its instructions.
     *
     * Validates: Requirements 6.5, 6.6
     */
    @Property(tries = 100)
    void resolveReturnsVariantInstructionsWhenVariantExists(
            @ForAll("variantResolutionWithMatch") ResolutionInput input) {

        SkillVariantRepository variantRepo = mock(SkillVariantRepository.class);

        // Set up: variant exists for the requested tool
        SkillVariantEntity variant = new SkillVariantEntity();
        variant.setId(UUID.randomUUID());
        variant.setSkillVersionId(input.skillVersionId());
        variant.setTool(input.requestedTool());
        variant.setInstructions(input.expectedInstructions());
        variant.setCreatedAt(Instant.now());
        variant.setUpdatedAt(Instant.now());

        when(variantRepo.findBySkillVersionIdAndTool(input.skillVersionId(), input.requestedTool()))
                .thenReturn(Optional.of(variant));

        SkillVariantServiceImpl service = buildServiceWithVariantRepo(variantRepo);

        String result = service.resolve(input.skillVersionId(), input.requestedTool());

        assertThat(result).isEqualTo(input.expectedInstructions());
    }

    /**
     * Property 10: When no variant exists for the requested tool, resolve() returns null.
     *
     * Validates: Requirements 6.5, 6.6
     */
    @Property(tries = 100)
    void resolveReturnsNullWhenNoVariantExists(
            @ForAll("variantResolutionNoMatch") ResolutionInput input) {

        SkillVariantRepository variantRepo = mock(SkillVariantRepository.class);

        // No variant exists for the requested tool
        when(variantRepo.findBySkillVersionIdAndTool(input.skillVersionId(), input.requestedTool()))
                .thenReturn(Optional.empty());

        SkillVariantServiceImpl service = buildServiceWithVariantRepo(variantRepo);

        String result = service.resolve(input.skillVersionId(), input.requestedTool());

        assertThat(result).isNull();
    }

    // ===== Property 11: Unique variant per tool per version =====

    /**
     * Property 11: Creating a variant when one already exists for the same tool on the same version
     * throws SkillConflictException.
     *
     * Validates: Requirements 6.4
     */
    @Property(tries = 100)
    void createThrowsConflictWhenVariantAlreadyExistsForToolAndVersion(
            @ForAll("duplicateVariantInputs") DuplicateVariantInput input) {

        SkillVariantRepository variantRepo = mock(SkillVariantRepository.class);
        SkillVersionRepository versionRepo = mock(SkillVersionRepository.class);
        SkillService skillService = mock(SkillService.class);
        TeamMemberRepository teamMemberRepo = mock(TeamMemberRepository.class);
        AuditService auditService = mock(AuditService.class);

        UUID orgId = input.userDetails().organizationId();

        // Stub skill lookup (visibility check passes)
        SkillEntity skill = new SkillEntity();
        skill.setId(input.skillId());
        skill.setOrganizationId(orgId);
        skill.setOwnerTeamId(input.ownerTeamId());
        skill.setName("Test Skill");
        skill.setSlug("test-skill");
        skill.setDescription("Test description");
        skill.setCreatedBy(input.userDetails().userId());
        when(skillService.getById(input.skillId(), input.userDetails())).thenReturn(skill);

        // Stub team membership for edit authority (non-admin users)
        if (!input.userDetails().role().isAdmin()) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(input.ownerTeamId());
            member.setUserId(input.userDetails().userId());
            member.setTeamRole("member");
            when(teamMemberRepo.findByTeamIdAndUserId(input.ownerTeamId(), input.userDetails().userId()))
                    .thenReturn(Optional.of(member));
        }

        // Stub version lookup
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(input.versionId());
        version.setSkillId(input.skillId());
        version.setVersionNumber(input.versionNumber());
        version.setCanonicalInstructions("Canonical instructions");
        when(versionRepo.findBySkillIdAndVersionNumber(input.skillId(), input.versionNumber()))
                .thenReturn(Optional.of(version));

        // Stub: variant ALREADY exists for this tool + version
        when(variantRepo.existsBySkillVersionIdAndTool(input.versionId(), input.tool()))
                .thenReturn(true);

        SkillVariantServiceImpl service = new SkillVariantServiceImpl(
                variantRepo, versionRepo, skillService, teamMemberRepo, auditService);

        assertThatThrownBy(() -> service.create(
                input.skillId(), input.versionNumber(), input.tool(),
                input.instructions(), input.userDetails()))
                .isInstanceOf(SkillConflictException.class)
                .hasMessageContaining(input.tool());
    }

    // ===== Test infrastructure =====

    record ResolutionInput(
            UUID skillVersionId,
            String requestedTool,
            String expectedInstructions
    ) {}

    record DuplicateVariantInput(
            UUID skillId,
            UUID versionId,
            UUID ownerTeamId,
            int versionNumber,
            String tool,
            String instructions,
            AlexandriaUserDetails userDetails
    ) {}

    private SkillVariantServiceImpl buildServiceWithVariantRepo(SkillVariantRepository variantRepo) {
        SkillVersionRepository versionRepo = mock(SkillVersionRepository.class);
        SkillService skillService = mock(SkillService.class);
        TeamMemberRepository teamMemberRepo = mock(TeamMemberRepository.class);
        AuditService auditService = mock(AuditService.class);

        return new SkillVariantServiceImpl(
                variantRepo, versionRepo, skillService, teamMemberRepo, auditService);
    }

    // ===== Providers =====

    @Provide
    Arbitrary<ResolutionInput> variantResolutionWithMatch() {
        Arbitrary<UUID> versionIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> tools = Arbitraries.of(SUPPORTED_TOOLS);
        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 \n#*-_")
                .ofMinLength(1)
                .ofMaxLength(500);

        return Combinators.combine(versionIds, tools, instructions)
                .as(ResolutionInput::new);
    }

    @Provide
    Arbitrary<ResolutionInput> variantResolutionNoMatch() {
        Arbitrary<UUID> versionIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> tools = Arbitraries.of(SUPPORTED_TOOLS);
        // Include a dummy string arbitrary to increase combinatorial space beyond exhaustive threshold
        Arbitrary<String> dummies = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz")
                .ofMinLength(1)
                .ofMaxLength(10);

        return Combinators.combine(versionIds, tools, dummies)
                .as((versionId, tool, _dummy) -> new ResolutionInput(versionId, tool, null));
    }

    @Provide
    Arbitrary<DuplicateVariantInput> duplicateVariantInputs() {
        Arbitrary<UUID> skillIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> versionIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> teamIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Integer> versionNumbers = Arbitraries.integers().between(1, 50);
        Arbitrary<String> tools = Arbitraries.of(SUPPORTED_TOOLS);
        Arbitrary<String> instructions = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 \n#*-_")
                .ofMinLength(1)
                .ofMaxLength(500);

        Arbitrary<AlexandriaUserDetails> users = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(Role.Skill_Author, Role.Team_Owner, Role.Catalog_Admin, Role.Org_Admin)
        ).as((userId, orgId, role) -> new AlexandriaUserDetails(userId, orgId, role, "user@test.com"));

        return Combinators.combine(skillIds, versionIds, teamIds, versionNumbers, tools, instructions, users)
                .as(DuplicateVariantInput::new);
    }
}
