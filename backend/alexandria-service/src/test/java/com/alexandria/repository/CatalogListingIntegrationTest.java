package com.alexandria.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.CatalogEntryProjection;
import com.alexandria.skill.SkillAccessGrantRepository;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillAccessGrantEntity;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.UserEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the resident Skill Catalog listing query
 * {@link SkillResolverRepository#findVisibleCatalogEntries} against a real PostgreSQL (Testcontainers,
 * {@code pgvector/pgvector:pg16} — the project's standard image).
 *
 * <p>Covers skill-catalog Task 2:</p>
 * <ul>
 *   <li><b>ACL-correct results (R1.2):</b> organization/team/private/restricted visibility, org
 *       scoping, and the org-admin bypass are honored.</li>
 *   <li><b>Lifecycle exclusions (R1.2):</b> Blocked always excluded; Archived excluded unless
 *       requested; version-less skills excluded.</li>
 *   <li><b>Scoping (R6):</b> {@code approvedOnly} narrows to Company_Approved/Team_Approved only.</li>
 *   <li><b>Deterministic ordering (R1.4):</b> results ordered by name.</li>
 *   <li><b>ACL parity / drift-guard (R1.5):</b> for an FTS-matchable corpus, the catalog listing
 *       returns the exact same skill set as {@link SkillResolverRepository#findVisibleCandidates},
 *       proving the copied ACL predicate did not drift.</li>
 * </ul>
 *
 * <p>Validates: skill-catalog Requirements R1.1, R1.2, R1.3, R1.4, R1.5, R6.1, R6.2</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(CatalogListingIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("Catalog listing query (findVisibleCatalogEntries)")
class CatalogListingIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
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
    private SkillResolverRepository skillResolverRepository;
    @Autowired
    private SkillVersionRepository skillVersionRepository;
    @Autowired
    private SkillAccessGrantRepository skillAccessGrantRepository;

    private static final String TOKEN = "catalogtoken";

    private OrganizationEntity org;
    private UserEntity userA;
    private UserEntity userB;
    private TeamEntity teamAlpha;

    @BeforeEach
    void setUp() {
        skillAccessGrantRepository.deleteAll();
        skillVersionRepository.deleteAll();
        skillRepository.deleteAll();
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();
        userRepository.deleteAll();

        org = organizationRepository.findBySlug("cat-test-org")
                .orElseGet(() -> {
                    var o = new OrganizationEntity();
                    o.setName("Catalog Test Org");
                    o.setSlug("cat-test-org");
                    return organizationRepository.saveAndFlush(o);
                });

        userA = createUser(org.getId(), "cat-user-a", "cat-a@test.com");
        userB = createUser(org.getId(), "cat-user-b", "cat-b@test.com");

        teamAlpha = createTeam(org.getId(), "Alpha", "cat-alpha", userA.getId());
        createTeamMember(teamAlpha.getId(), userA.getId(), "owner");
    }

    private List<CatalogEntryProjection> catalog(UUID userId, boolean isOrgAdmin,
                                                 boolean includeArchived, boolean approvedOnly) {
        return skillResolverRepository.findVisibleCatalogEntries(
                org.getId(), userId, isOrgAdmin, includeArchived, approvedOnly, 500);
    }

    private List<CandidateProjection> fts(UUID userId, boolean isOrgAdmin, boolean includeArchived) {
        return skillResolverRepository.findVisibleCandidates(
                org.getId(), userId, isOrgAdmin, includeArchived, TOKEN, 500);
    }

    private static Set<UUID> ids(List<CatalogEntryProjection> entries) {
        return entries.stream().map(CatalogEntryProjection::getSkillId).collect(Collectors.toSet());
    }

    private static Set<UUID> ftsIds(List<CandidateProjection> c) {
        return c.stream().map(CandidateProjection::getSkillId).collect(Collectors.toSet());
    }

    // ---------------- ACL correctness (R1.2) ----------------

    @Test
    @DisplayName("org-visible skill to any org member; team skill only to members")
    void orgAndTeamVisibility() {
        SkillEntity orgSkill = skill("cat-org", "organization", "Published", userA.getId());
        SkillEntity teamSkill = skill("cat-team", "team", "Published", userA.getId());

        assertThat(ids(catalog(userA.getId(), false, false, false)))
                .contains(orgSkill.getId(), teamSkill.getId());

        Set<UUID> catB = ids(catalog(userB.getId(), false, false, false));
        assertThat(catB).contains(orgSkill.getId());
        assertThat(catB).doesNotContain(teamSkill.getId());
    }

    @Test
    @DisplayName("private only to creator; restricted to creator or granted user")
    void privateAndRestrictedVisibility() {
        SkillEntity priv = skill("cat-priv", "private", "Published", userA.getId());
        SkillEntity restricted = skill("cat-restr", "restricted", "Published", userA.getId());
        grant(restricted.getId(), userB.getId());

        assertThat(ids(catalog(userA.getId(), false, false, false)))
                .contains(priv.getId(), restricted.getId());

        Set<UUID> catB = ids(catalog(userB.getId(), false, false, false));
        assertThat(catB).doesNotContain(priv.getId());
        assertThat(catB).contains(restricted.getId());
    }

    @Test
    @DisplayName("org admin bypasses visibility; cross-org skills never leak")
    void orgAdminAndOrgScoping() {
        SkillEntity priv = skill("cat-admin-priv", "private", "Published", userA.getId());
        assertThat(ids(catalog(userB.getId(), true, false, false))).contains(priv.getId());

        SkillEntity foreign = foreignOrgSkill();
        assertThat(ids(catalog(userB.getId(), true, false, false))).doesNotContain(foreign.getId());
    }

    @Test
    @DisplayName("Blocked excluded always; Archived excluded unless includeArchived; version-less excluded")
    void lifecycleExclusions() {
        SkillEntity published = skill("cat-pub", "organization", "Published", userA.getId());
        SkillEntity archived = skill("cat-arch", "organization", "Archived", userA.getId());
        SkillEntity blocked = skill("cat-block", "organization", "Blocked", userA.getId());
        SkillEntity versionless = versionlessSkill("cat-noversion", "organization", userA.getId());

        Set<UUID> defaultView = ids(catalog(userA.getId(), true, false, false));
        assertThat(defaultView).contains(published.getId());
        assertThat(defaultView).doesNotContain(archived.getId(), blocked.getId(), versionless.getId());

        Set<UUID> withArchived = ids(catalog(userA.getId(), true, true, false));
        assertThat(withArchived).contains(published.getId(), archived.getId());
        assertThat(withArchived).doesNotContain(blocked.getId(), versionless.getId());
    }

    // ---------------- scoping (R6) ----------------

    @Test
    @DisplayName("approvedOnly narrows the catalog to Company_Approved / Team_Approved")
    void approvedOnlyScoping() {
        SkillEntity published = skill("cat-s-pub", "organization", "Published", userA.getId());
        SkillEntity companyApproved =
                skill("cat-s-company", "organization", "Company_Approved", userA.getId());
        SkillEntity teamApproved =
                skill("cat-s-team", "organization", "Team_Approved", userA.getId());

        Set<UUID> all = ids(catalog(userA.getId(), true, false, false));
        assertThat(all).contains(published.getId(), companyApproved.getId(), teamApproved.getId());

        Set<UUID> approvedOnly = ids(catalog(userA.getId(), true, false, true));
        assertThat(approvedOnly).contains(companyApproved.getId(), teamApproved.getId());
        assertThat(approvedOnly).doesNotContain(published.getId());
    }

    // ---------------- ordering (R1.4) ----------------

    @Test
    @DisplayName("results are ordered deterministically by name")
    void deterministicOrderingByName() {
        skill("cat-zeta", "organization", "Published", userA.getId());  // name = "catalogtoken cat-zeta"
        skill("cat-alpha-name", "organization", "Published", userA.getId());
        skill("cat-mid", "organization", "Published", userA.getId());

        List<CatalogEntryProjection> entries = catalog(userA.getId(), true, false, false);
        List<String> names = entries.stream().map(CatalogEntryProjection::getName).toList();
        assertThat(names).isSorted();
    }

    @Test
    @DisplayName("projection exposes the current version id for direct fetch")
    void projectionCarriesVersionId() {
        SkillEntity s = skill("cat-vid", "organization", "Published", userA.getId());
        CatalogEntryProjection entry = catalog(userA.getId(), true, false, false).stream()
                .filter(e -> e.getSkillId().equals(s.getId())).findFirst().orElseThrow();
        assertThat(entry.getSkillVersionId()).isEqualTo(s.getCurrentVersionId());
        assertThat(entry.getName()).isNotBlank();
        assertThat(entry.getVisibility()).isEqualTo("organization");
    }

    // ---------------- ACL parity / drift-guard (R1.5) ----------------

    @Test
    @DisplayName("catalog ACL predicate stays in parity with the resolver's findVisibleCandidates")
    void aclParityWithResolver() {
        // One FTS-matchable skill at every visibility level. Because each skill shares TOKEN, the FTS
        // query surfaces exactly the ACL-visible set — so set equality is a direct drift-guard on the
        // copied ACL/lifecycle predicate (the catalog has no FTS predicate, but the ACL must match).
        skill("par-org", "organization", "Published", userA.getId());
        skill("par-team", "team", "Published", userA.getId());
        skill("par-priv", "private", "Published", userA.getId());
        SkillEntity restricted = skill("par-restr", "restricted", "Published", userA.getId());
        grant(restricted.getId(), userB.getId());
        skill("par-arch", "organization", "Archived", userA.getId());
        skill("par-block", "organization", "Blocked", userA.getId());

        assertAclParity(userA.getId(), false, false);
        assertAclParity(userA.getId(), false, true);
        assertAclParity(userB.getId(), false, false);
        assertAclParity(userB.getId(), false, true);
        assertAclParity(userB.getId(), true, false);
        assertAclParity(userB.getId(), true, true);
    }

    private void assertAclParity(UUID userId, boolean isOrgAdmin, boolean includeArchived) {
        Set<UUID> catalogIds = ids(catalog(userId, isOrgAdmin, includeArchived, false));
        Set<UUID> resolverIds = ftsIds(fts(userId, isOrgAdmin, includeArchived));
        assertThat(catalogIds)
                .as("catalog/resolver ACL parity for user=%s admin=%s archived=%s",
                        userId, isOrgAdmin, includeArchived)
                .isEqualTo(resolverIds);
    }

    // ---------------- helpers ----------------

    /** A skill + current version whose indexed text contains {@link #TOKEN} (FTS-matchable). */
    private SkillEntity skill(String slug, String visibility, String status, UUID createdBy) {
        SkillEntity skill = new SkillEntity();
        skill.setOrganizationId(org.getId());
        skill.setOwnerTeamId(teamAlpha.getId());
        skill.setName(TOKEN + " " + slug);
        skill.setSlug(slug);
        skill.setDescription("desc " + TOKEN);
        skill.setVisibility(visibility);
        skill.setLifecycleStatus(status);
        skill.setCreatedBy(createdBy);
        skill = skillRepository.saveAndFlush(skill);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions(TOKEN + " instructions for " + slug);
        version = skillVersionRepository.saveAndFlush(version);

        skill.setCurrentVersionId(version.getId());
        skill = skillRepository.saveAndFlush(skill);

        skillRepository.rebuildSearchVector(skill.getId(), "", TOKEN + " instructions for " + slug);
        return skill;
    }

    /** A skill with no current version (should never appear in the catalog). */
    private SkillEntity versionlessSkill(String slug, String visibility, UUID createdBy) {
        SkillEntity skill = new SkillEntity();
        skill.setOrganizationId(org.getId());
        skill.setOwnerTeamId(teamAlpha.getId());
        skill.setName(TOKEN + " " + slug);
        skill.setSlug(slug);
        skill.setDescription("desc " + TOKEN);
        skill.setVisibility(visibility);
        skill.setLifecycleStatus("Published");
        skill.setCreatedBy(createdBy);
        return skillRepository.saveAndFlush(skill); // no currentVersionId set
    }

    private SkillEntity foreignOrgSkill() {
        OrganizationEntity other = organizationRepository.findBySlug("cat-other-org")
                .orElseGet(() -> {
                    var o = new OrganizationEntity();
                    o.setName("Other Org");
                    o.setSlug("cat-other-org");
                    return organizationRepository.saveAndFlush(o);
                });
        UserEntity otherUser = createUser(other.getId(), "cat-other-user", "cat-other@test.com");
        TeamEntity otherTeam = createTeam(other.getId(), "Other", "cat-other-team", otherUser.getId());

        SkillEntity skill = new SkillEntity();
        skill.setOrganizationId(other.getId());
        skill.setOwnerTeamId(otherTeam.getId());
        skill.setName(TOKEN + " foreign");
        skill.setSlug("cat-foreign");
        skill.setDescription("desc " + TOKEN);
        skill.setVisibility("organization");
        skill.setLifecycleStatus("Published");
        skill.setCreatedBy(otherUser.getId());
        skill = skillRepository.saveAndFlush(skill);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions(TOKEN + " foreign instructions");
        version = skillVersionRepository.saveAndFlush(version);
        skill.setCurrentVersionId(version.getId());
        return skillRepository.saveAndFlush(skill);
    }

    private void grant(UUID skillId, UUID userId) {
        SkillAccessGrantEntity g = new SkillAccessGrantEntity();
        g.setSkillId(skillId);
        g.setUserId(userId);
        skillAccessGrantRepository.saveAndFlush(g);
    }

    private UserEntity createUser(UUID orgId, String subject, String email) {
        var user = new UserEntity();
        user.setOrganizationId(orgId);
        user.setExternalSubject(subject);
        user.setEmail(email);
        user.setRole("Skill_Consumer");
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private TeamEntity createTeam(UUID orgId, String name, String slug, UUID createdBy) {
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
