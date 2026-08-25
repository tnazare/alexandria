package com.alexandria.repository;

import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.SkillAccessGrantRepository;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillTagRepository;
import com.alexandria.skill.SkillVariantRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillAccessGrantEntity;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillTagEntity;
import com.alexandria.skill.entity.SkillVariantEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for V4 migration and skill repository layer.
 *
 * <p>Validates that Flyway V4 migration applies cleanly on a real PostgreSQL instance
 * and that skill-related repository operations (CRUD, unique constraints, visibility queries,
 * full-text search) work correctly.</p>
 *
 * <p>Validates: Requirements 1.2, 5.5, 6.4, 7.10</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(V4MigrationAndSkillRepositoryIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("V4 Migration and Skill Repository Integration Tests")
class V4MigrationAndSkillRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("alexandria_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;
    @Autowired
    private SkillRepository skillRepository;
    @Autowired
    private SkillVersionRepository skillVersionRepository;
    @Autowired
    private SkillVariantRepository skillVariantRepository;
    @Autowired
    private SkillTagRepository skillTagRepository;
    @Autowired
    private SkillAccessGrantRepository skillAccessGrantRepository;
    @Autowired
    private TaxonomyEntryRepository taxonomyEntryRepository;

    private OrganizationEntity org;
    private UserEntity userA;
    private UserEntity userB;
    private TeamEntity teamAlpha;
    private TeamEntity teamBeta;

    @BeforeEach
    void setUp() {
        // Clean up in reverse dependency order
        skillAccessGrantRepository.deleteAll();
        skillTagRepository.deleteAll();
        skillVariantRepository.deleteAll();
        skillVersionRepository.deleteAll();
        skillRepository.deleteAll();
        taxonomyEntryRepository.deleteAll();
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();
        userRepository.deleteAll();

        // Create organization
        org = organizationRepository.findBySlug("v4-test-org")
                .orElseGet(() -> {
                    var o = new OrganizationEntity();
                    o.setName("V4 Test Org");
                    o.setSlug("v4-test-org");
                    return organizationRepository.saveAndFlush(o);
                });

        // Create users
        userA = createUser("user-a-subject", "usera@test.com");
        userB = createUser("user-b-subject", "userb@test.com");

        // Create teams
        teamAlpha = createTeam("Alpha Team", "alpha-team", userA.getId());
        teamBeta = createTeam("Beta Team", "beta-team", userB.getId());

        // Add userA as member of teamAlpha
        createTeamMember(teamAlpha.getId(), userA.getId(), "owner");
        // Add userB as member of teamBeta
        createTeamMember(teamBeta.getId(), userB.getId(), "owner");
    }

    @Nested
    @DisplayName("V4 Migration")
    class MigrationTests {

        @Test
        @DisplayName("V4 migration applies cleanly - all skill tables accept inserts")
        void migrationAppliesCleanly() {
            // Context loads with ddl-auto=validate, proving migration matches JPA entities.
            // Verify we can insert into all V4 tables.
            var skill = createSkill(teamAlpha.getId(), "Test Skill", "test-skill",
                    "A test skill", userA.getId());
            assertThat(skill.getId()).isNotNull();

            var version = createSkillVersion(skill.getId(), 1, "Do the thing");
            assertThat(version.getId()).isNotNull();

            var variant = createSkillVariant(version.getId(), "github_copilot", "Copilot-specific instructions");
            assertThat(variant.getId()).isNotNull();

            var taxonomy = createTaxonomyEntry("tool", "copilot");
            assertThat(taxonomy.getId()).isNotNull();

            var tag = createSkillTag(skill.getId(), taxonomy.getId());
            assertThat(tag.getId()).isNotNull();

            var grant = createAccessGrant(skill.getId(), userB.getId());
            assertThat(grant.getId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Unique Index Constraints")
    class UniqueConstraintTests {

        @Test
        @DisplayName("slug must be unique within same organization")
        void slugUniquenessWithinOrg() {
            createSkill(teamAlpha.getId(), "Skill One", "duplicate-slug", "Desc one", userA.getId());

            assertThatThrownBy(() ->
                    createSkill(teamAlpha.getId(), "Skill Two", "duplicate-slug", "Desc two", userA.getId())
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same slug in different organizations is allowed")
        void sameSlugDifferentOrgAllowed() {
            createSkill(teamAlpha.getId(), "Skill One", "shared-slug", "Desc", userA.getId());

            // Create a second organization and a skill with the same slug
            var org2 = organizationRepository.findBySlug("v4-test-org-2")
                    .orElseGet(() -> {
                        var o = new OrganizationEntity();
                        o.setName("V4 Test Org 2");
                        o.setSlug("v4-test-org-2");
                        return organizationRepository.saveAndFlush(o);
                    });
            var user2 = createUserInOrg(org2.getId(), "user-org2", "user@org2.com");
            var team2 = createTeamInOrg(org2.getId(), "Team Org2", "team-org2", user2.getId());

            var skill2 = new SkillEntity();
            skill2.setOrganizationId(org2.getId());
            skill2.setOwnerTeamId(team2.getId());
            skill2.setName("Skill One");
            skill2.setSlug("shared-slug");
            skill2.setDescription("Desc");
            skill2.setCreatedBy(user2.getId());
            var saved = skillRepository.saveAndFlush(skill2);

            assertThat(saved.getId()).isNotNull();
        }

        @Test
        @DisplayName("variant tool must be unique per skill version")
        void variantUniquenessPerVersion() {
            var skill = createSkill(teamAlpha.getId(), "Variant Skill", "variant-skill", "Desc", userA.getId());
            var version = createSkillVersion(skill.getId(), 1, "Instructions");

            createSkillVariant(version.getId(), "cursor", "Cursor instructions v1");

            assertThatThrownBy(() ->
                    createSkillVariant(version.getId(), "cursor", "Cursor instructions v2")
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same tool in different versions is allowed")
        void sameToolDifferentVersionsAllowed() {
            var skill = createSkill(teamAlpha.getId(), "Multi Version", "multi-version", "Desc", userA.getId());
            var v1 = createSkillVersion(skill.getId(), 1, "Instructions v1");
            var v2 = createSkillVersion(skill.getId(), 2, "Instructions v2");

            var variant1 = createSkillVariant(v1.getId(), "cursor", "Cursor v1");
            var variant2 = createSkillVariant(v2.getId(), "cursor", "Cursor v2");

            assertThat(variant1.getId()).isNotNull();
            assertThat(variant2.getId()).isNotNull();
        }

        @Test
        @DisplayName("taxonomy value must be unique per org and tag type (case-insensitive)")
        void taxonomyValueUniqueness() {
            createTaxonomyEntry("language", "Python");

            // Same value in same type and org (case difference) should violate unique index
            assertThatThrownBy(() ->
                    createTaxonomyEntry("language", "python")
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same taxonomy value in different tag types is allowed")
        void sameTaxonomyValueDifferentTypes() {
            var entry1 = createTaxonomyEntry("tool", "python");
            var entry2 = createTaxonomyEntry("language", "python");

            assertThat(entry1.getId()).isNotNull();
            assertThat(entry2.getId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("findVisibleSkills Native Query")
    class VisibilityQueryTests {

        private SkillEntity orgSkill;
        private SkillEntity teamSkill;
        private SkillEntity privateSkill;
        private SkillEntity restrictedSkill;

        @BeforeEach
        void setUpSkills() {
            // Organization-visible skill (owned by teamAlpha, created by userA)
            orgSkill = createSkill(teamAlpha.getId(), "Org Skill", "org-skill", "Visible to all", userA.getId());
            orgSkill.setVisibility("organization");
            skillRepository.saveAndFlush(orgSkill);

            // Team-visible skill (owned by teamAlpha, created by userA)
            teamSkill = createSkill(teamAlpha.getId(), "Team Skill", "team-skill", "Visible to team", userA.getId());
            teamSkill.setVisibility("team");
            skillRepository.saveAndFlush(teamSkill);

            // Private skill (owned by teamAlpha, created by userA)
            privateSkill = createSkill(teamAlpha.getId(), "Private Skill", "private-skill", "Only creator", userA.getId());
            privateSkill.setVisibility("private");
            skillRepository.saveAndFlush(privateSkill);

            // Restricted skill (owned by teamAlpha, created by userA, userB granted access)
            restrictedSkill = createSkill(teamAlpha.getId(), "Restricted Skill", "restricted-skill", "By grant only", userA.getId());
            restrictedSkill.setVisibility("restricted");
            skillRepository.saveAndFlush(restrictedSkill);
            createAccessGrant(restrictedSkill.getId(), userB.getId());
        }

        @Test
        @DisplayName("organization-visible skills are seen by all org members")
        void orgVisibilityForAllUsers() {
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userB.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(orgSkill.getId());
        }

        @Test
        @DisplayName("team-visible skill is seen by team member")
        void teamVisibilityForMember() {
            // userA is in teamAlpha — should see teamSkill
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(teamSkill.getId());
        }

        @Test
        @DisplayName("team-visible skill is NOT seen by non-team member")
        void teamVisibilityHiddenFromNonMember() {
            // userB is NOT in teamAlpha — should not see teamSkill
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userB.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .doesNotContain(teamSkill.getId());
        }

        @Test
        @DisplayName("private skill is seen only by creator")
        void privateVisibilityCreatorOnly() {
            // userA is creator — should see privateSkill
            var pageA = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, null, PageRequest.of(0, 50));
            assertThat(pageA.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(privateSkill.getId());

            // userB is not creator — should not see privateSkill
            var pageB = skillRepository.findVisibleSkills(
                    org.getId(), userB.getId(), false, null, null, null, PageRequest.of(0, 50));
            assertThat(pageB.getContent())
                    .extracting(SkillEntity::getId)
                    .doesNotContain(privateSkill.getId());
        }

        @Test
        @DisplayName("restricted skill is seen by granted user")
        void restrictedVisibilityGrantedUser() {
            // userB has been granted access
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userB.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(restrictedSkill.getId());
        }

        @Test
        @DisplayName("restricted skill is NOT seen by non-granted, non-creator user")
        void restrictedVisibilityHiddenFromNonGrantedUser() {
            // Create a third user with no grants
            var userC = createUser("user-c-subject", "userc@test.com");

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userC.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .doesNotContain(restrictedSkill.getId());
        }

        @Test
        @DisplayName("restricted skill is seen by its creator")
        void restrictedVisibilityCreatorCanSee() {
            // userA created the restrictedSkill
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(restrictedSkill.getId());
        }

        @Test
        @DisplayName("org admin sees all skills regardless of visibility")
        void orgAdminSeesAll() {
            var userC = createUser("admin-user", "admin@test.com");

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userC.getId(), true, null, null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(orgSkill.getId(), teamSkill.getId(), privateSkill.getId(), restrictedSkill.getId());
        }

        @Test
        @DisplayName("status filter narrows results")
        void statusFilter() {
            orgSkill.setLifecycleStatus("Published");
            skillRepository.saveAndFlush(orgSkill);

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), true, "Published", null, null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(orgSkill.getId())
                    .doesNotContain(teamSkill.getId(), privateSkill.getId(), restrictedSkill.getId());
        }

        @Test
        @DisplayName("teamId filter narrows results")
        void teamIdFilter() {
            // Create a skill on teamBeta
            var betaSkill = createSkill(teamBeta.getId(), "Beta Skill", "beta-skill",
                    "Beta desc", userB.getId());
            betaSkill.setVisibility("organization");
            skillRepository.saveAndFlush(betaSkill);

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), true, null, teamBeta.getId(), null, PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .contains(betaSkill.getId())
                    .doesNotContain(orgSkill.getId(), teamSkill.getId());
        }
    }

    @Nested
    @DisplayName("Full-Text Search via search_vector GIN Index")
    class FullTextSearchTests {

        @Test
        @DisplayName("search by skill name matches")
        void searchByName() {
            createSkill(teamAlpha.getId(), "Kubernetes Deployment Automation", "k8s-deploy",
                    "Automates K8s deployments", userA.getId())
                    .setVisibility("organization");
            skillRepository.saveAndFlush(skillRepository.findByOrganizationIdAndSlug(org.getId(), "k8s-deploy").get());

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, "Kubernetes", PageRequest.of(0, 50));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent().get(0).getSlug()).isEqualTo("k8s-deploy");
        }

        @Test
        @DisplayName("search by description matches")
        void searchByDescription() {
            var skill = createSkill(teamAlpha.getId(), "Generic Workflow", "generic-wf",
                    "This skill helps with PostgreSQL database migrations", userA.getId());
            skill.setVisibility("organization");
            skillRepository.saveAndFlush(skill);

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, "PostgreSQL", PageRequest.of(0, 50));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent())
                    .extracting(SkillEntity::getSlug)
                    .contains("generic-wf");
        }

        @Test
        @DisplayName("search with no match returns empty")
        void searchNoMatch() {
            createSkill(teamAlpha.getId(), "Java Skill", "java-skill", "Java programming", userA.getId())
                    .setVisibility("organization");
            skillRepository.saveAndFlush(skillRepository.findByOrganizationIdAndSlug(org.getId(), "java-skill").get());

            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, "xyznonexistent", PageRequest.of(0, 50));

            assertThat(page.getContent()).isEmpty();
        }

        @Test
        @DisplayName("search respects visibility - private skills not found by other users")
        void searchRespectsVisibility() {
            var skill = createSkill(teamAlpha.getId(), "Secret Machine Learning Workflow", "secret-ml",
                    "Top secret ML pipeline", userA.getId());
            skill.setVisibility("private");
            skillRepository.saveAndFlush(skill);

            // userB should not find this private skill even when searching by name
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userB.getId(), false, null, null, "Machine Learning", PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getId)
                    .doesNotContain(skill.getId());
        }

        @Test
        @DisplayName("search uses stemming (plural/singular forms match)")
        void searchUsesStemming() {
            var skill = createSkill(teamAlpha.getId(), "Code Review Automation", "code-review",
                    "Automates code reviews for pull requests", userA.getId());
            skill.setVisibility("organization");
            skillRepository.saveAndFlush(skill);

            // Search with singular "review" should match "reviews" via stemming
            var page = skillRepository.findVisibleSkills(
                    org.getId(), userA.getId(), false, null, null, "review", PageRequest.of(0, 50));

            assertThat(page.getContent())
                    .extracting(SkillEntity::getSlug)
                    .contains("code-review");
        }
    }

    // --- Helper methods ---

    private UserEntity createUser(String subject, String email) {
        return createUserInOrg(org.getId(), subject, email);
    }

    private UserEntity createUserInOrg(UUID orgId, String subject, String email) {
        var user = new UserEntity();
        user.setOrganizationId(orgId);
        user.setExternalSubject(subject);
        user.setEmail(email);
        user.setRole("Skill_Consumer");
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private TeamEntity createTeam(String name, String slug, UUID createdBy) {
        return createTeamInOrg(org.getId(), name, slug, createdBy);
    }

    private TeamEntity createTeamInOrg(UUID orgId, String name, String slug, UUID createdBy) {
        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName(name);
        team.setSlug(slug);
        team.setCreatedBy(createdBy);
        return teamRepository.saveAndFlush(team);
    }

    private TeamMemberEntity createTeamMember(UUID teamId, UUID userId, String role) {
        var member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole(role);
        return teamMemberRepository.saveAndFlush(member);
    }

    private SkillEntity createSkill(UUID ownerTeamId, String name, String slug, String description, UUID createdBy) {
        var skill = new SkillEntity();
        skill.setOrganizationId(org.getId());
        skill.setOwnerTeamId(ownerTeamId);
        skill.setName(name);
        skill.setSlug(slug);
        skill.setDescription(description);
        skill.setCreatedBy(createdBy);
        return skillRepository.saveAndFlush(skill);
    }

    private SkillVersionEntity createSkillVersion(UUID skillId, int versionNumber, String instructions) {
        var version = new SkillVersionEntity();
        version.setSkillId(skillId);
        version.setVersionNumber(versionNumber);
        version.setCanonicalInstructions(instructions);
        return skillVersionRepository.saveAndFlush(version);
    }

    private SkillVariantEntity createSkillVariant(UUID skillVersionId, String tool, String instructions) {
        var variant = new SkillVariantEntity();
        variant.setSkillVersionId(skillVersionId);
        variant.setTool(tool);
        variant.setInstructions(instructions);
        return skillVariantRepository.saveAndFlush(variant);
    }

    private TaxonomyEntryEntity createTaxonomyEntry(String tagType, String value) {
        var entry = new TaxonomyEntryEntity();
        entry.setOrganizationId(org.getId());
        entry.setTagType(tagType);
        entry.setValue(value);
        entry.setActive(true);
        return taxonomyEntryRepository.saveAndFlush(entry);
    }

    private SkillTagEntity createSkillTag(UUID skillId, UUID taxonomyEntryId) {
        var tag = new SkillTagEntity();
        tag.setSkillId(skillId);
        tag.setTaxonomyEntryId(taxonomyEntryId);
        return skillTagRepository.saveAndFlush(tag);
    }

    private SkillAccessGrantEntity createAccessGrant(UUID skillId, UUID userId) {
        var grant = new SkillAccessGrantEntity();
        grant.setSkillId(skillId);
        grant.setUserId(userId);
        return skillAccessGrantRepository.saveAndFlush(grant);
    }

    /**
     * Mock JwtDecoder to avoid needing a real OIDC provider during repository tests.
     */
    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        public JwtDecoder jwtDecoder() {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.JwtException("Not used in repository tests");
            };
        }
    }
}
