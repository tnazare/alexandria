package com.alexandria.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.alexandria.embedding.EmbeddingProvider;
import com.alexandria.embedding.EmbeddingRepository;
import com.alexandria.embedding.EmbeddingService;
import com.alexandria.embedding.LocalEmbeddingProvider;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.CandidateProjection;
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
 * Integration tests for the semantic retrieval query
 * {@link SkillResolverRepository#findVisibleCandidatesSemantic} against a real PostgreSQL with the
 * pgvector extension (Testcontainers, {@code pgvector/pgvector:pg16}).
 *
 * <p>Covers Task 6's acceptance criteria:</p>
 * <ul>
 *   <li><b>ACL-correct results (R4.1, R4.2):</b> organization/team/private/restricted visibility and
 *       org scoping are honored exactly as in the FTS query.</li>
 *   <li><b>Unembedded skills excluded (R4.3):</b> a visible, matchable skill with no embedding row is
 *       absent from semantic ranking (the {@code JOIN skill_embeddings} drops it).</li>
 *   <li><b>ACL parity / drift-guard (R4.4):</b> across several visibility scenarios, the set of skills
 *       the semantic query returns is identical to the set the FTS query returns for the same caller
 *       and corpus — proving the ACL predicate did not drift between the two copies.</li>
 * </ul>
 *
 * <p>The corpus is built so every skill is both FTS-matchable (shares a token) and embedded (same
 * assembled text), so any difference between the two result sets is attributable to the ACL
 * predicate, not to a skill that only one mechanism could surface.</p>
 *
 * <p>Validates: semantic-search Requirements R4.1, R4.2, R4.3, R4.4</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(SemanticRetrievalIntegrationTest.JwtDecoderTestConfig.class)
@DisplayName("Semantic retrieval query (findVisibleCandidatesSemantic)")
class SemanticRetrievalIntegrationTest {

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
    @Autowired
    private EmbeddingRepository embeddingRepository;

    // 256-dim to match the skill_embeddings column and the default embedding-dim.
    private final EmbeddingProvider embeddingProvider = new LocalEmbeddingProvider(256);

    private static final String TOKEN = "semantictoken";

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

        org = organizationRepository.findBySlug("sem-test-org")
                .orElseGet(() -> {
                    var o = new OrganizationEntity();
                    o.setName("Semantic Test Org");
                    o.setSlug("sem-test-org");
                    return organizationRepository.saveAndFlush(o);
                });

        userA = createUser(org.getId(), "sem-user-a", "sem-a@test.com");
        userB = createUser(org.getId(), "sem-user-b", "sem-b@test.com");

        teamAlpha = createTeam(org.getId(), "Alpha", "sem-alpha", userA.getId());
        createTeamMember(teamAlpha.getId(), userA.getId(), "owner");
    }

    /** The pgvector literal for a query that shares the corpus token, used by the semantic query. */
    private String queryLiteral() {
        return EmbeddingService.toVectorLiteral(embeddingProvider.embed(TOKEN + " skill"));
    }

    private List<CandidateProjection> semantic(UUID userId, boolean isOrgAdmin, boolean includeArchived) {
        return skillResolverRepository.findVisibleCandidatesSemantic(
                org.getId(), userId, isOrgAdmin, includeArchived, queryLiteral(), 50);
    }

    private List<CandidateProjection> fts(UUID userId, boolean isOrgAdmin, boolean includeArchived) {
        return skillResolverRepository.findVisibleCandidates(
                org.getId(), userId, isOrgAdmin, includeArchived, TOKEN, 50);
    }

    private static Set<UUID> ids(List<CandidateProjection> candidates) {
        return candidates.stream().map(CandidateProjection::getSkillId).collect(Collectors.toSet());
    }

    // ---------------- ACL correctness (R4.1, R4.2) ----------------

    @Test
    @DisplayName("org-visible skill is returned to any org member; team skill only to members")
    void orgAndTeamVisibility() {
        SkillEntity orgSkill = embeddedSkill("sem-org", "organization", "Published", userA.getId());
        SkillEntity teamSkill = embeddedSkill("sem-team", "team", "Published", userA.getId());

        // userA (team member) sees both.
        assertThat(ids(semantic(userA.getId(), false, false)))
                .contains(orgSkill.getId(), teamSkill.getId());

        // userB (non-member) sees the org skill but not the team skill.
        Set<UUID> candB = ids(semantic(userB.getId(), false, false));
        assertThat(candB).contains(orgSkill.getId());
        assertThat(candB).doesNotContain(teamSkill.getId());
    }

    @Test
    @DisplayName("private skill only to creator; restricted to creator or granted user")
    void privateAndRestrictedVisibility() {
        SkillEntity priv = embeddedSkill("sem-priv", "private", "Published", userA.getId());
        SkillEntity restricted = embeddedSkill("sem-restr", "restricted", "Published", userA.getId());
        grant(restricted.getId(), userB.getId());

        // Creator (userA) sees both.
        assertThat(ids(semantic(userA.getId(), false, false)))
                .contains(priv.getId(), restricted.getId());

        // userB: not creator → no private; granted → restricted visible.
        Set<UUID> candB = ids(semantic(userB.getId(), false, false));
        assertThat(candB).doesNotContain(priv.getId());
        assertThat(candB).contains(restricted.getId());
    }

    @Test
    @DisplayName("org admin bypasses visibility; cross-org skills never leak")
    void orgAdminAndOrgScoping() {
        SkillEntity priv = embeddedSkill("sem-admin-priv", "private", "Published", userA.getId());

        // An org admin sees the private skill.
        assertThat(ids(semantic(userB.getId(), true, false))).contains(priv.getId());

        // A skill in another org is never returned, even to an org admin of this org.
        SkillEntity foreign = embeddedForeignOrgSkill();
        assertThat(ids(semantic(userB.getId(), true, false))).doesNotContain(foreign.getId());
    }

    @Test
    @DisplayName("Blocked excluded always; Archived excluded unless includeArchived")
    void lifecycleExclusions() {
        SkillEntity published = embeddedSkill("sem-pub", "organization", "Published", userA.getId());
        SkillEntity archived = embeddedSkill("sem-arch", "organization", "Archived", userA.getId());
        SkillEntity blocked = embeddedSkill("sem-block", "organization", "Blocked", userA.getId());

        Set<UUID> defaultView = ids(semantic(userA.getId(), true, false));
        assertThat(defaultView).contains(published.getId());
        assertThat(defaultView).doesNotContain(archived.getId(), blocked.getId());

        Set<UUID> withArchived = ids(semantic(userA.getId(), true, true));
        assertThat(withArchived).contains(published.getId(), archived.getId());
        assertThat(withArchived).doesNotContain(blocked.getId());
    }

    // ---------------- unembedded exclusion (R4.3) ----------------

    @Test
    @DisplayName("a visible, matchable skill with no embedding is excluded from semantic ranking")
    void unembeddedSkillsExcluded() {
        SkillEntity embedded = embeddedSkill("sem-has-vec", "organization", "Published", userA.getId());
        SkillEntity unembedded = matchableSkill("sem-no-vec", "organization", "Published", userA.getId());
        // Deliberately NO embedding row for `unembedded`.

        // FTS surfaces both (both share the token); semantic surfaces only the embedded one.
        assertThat(ids(fts(userA.getId(), true, false)))
                .contains(embedded.getId(), unembedded.getId());

        Set<UUID> sem = ids(semantic(userA.getId(), true, false));
        assertThat(sem).contains(embedded.getId());
        assertThat(sem).doesNotContain(unembedded.getId());
    }

    // ---------------- ACL parity / drift-guard (R4.4) ----------------

    @Test
    @DisplayName("semantic ACL predicate stays in parity with the FTS ACL predicate")
    void aclParityBetweenFtsAndSemantic() {
        // One embedded, matchable skill at every visibility level. Because each skill is both
        // FTS-matchable and embedded, the only thing that can make the two result sets differ is the
        // ACL/lifecycle predicate — so set equality is a direct drift-guard on that predicate.
        embeddedSkill("par-org", "organization", "Published", userA.getId());
        embeddedSkill("par-team", "team", "Published", userA.getId());
        embeddedSkill("par-priv", "private", "Published", userA.getId());
        SkillEntity restricted = embeddedSkill("par-restr", "restricted", "Published", userA.getId());
        grant(restricted.getId(), userB.getId());
        embeddedSkill("par-arch", "organization", "Archived", userA.getId());
        embeddedSkill("par-block", "organization", "Blocked", userA.getId());

        // Compare the visible SET (order differs — FTS ranks by ts_rank, semantic by cosine) across a
        // spread of callers and the includeArchived toggle.
        assertAclParity(userA.getId(), false, false); // team member, no archived
        assertAclParity(userA.getId(), false, true);  // team member, with archived
        assertAclParity(userB.getId(), false, false); // non-member + granted-restricted
        assertAclParity(userB.getId(), false, true);
        assertAclParity(userB.getId(), true, false);   // org admin bypass
        assertAclParity(userB.getId(), true, true);
    }

    private void assertAclParity(UUID userId, boolean isOrgAdmin, boolean includeArchived) {
        Set<UUID> ftsIds = ids(fts(userId, isOrgAdmin, includeArchived));
        Set<UUID> semanticIds = ids(semantic(userId, isOrgAdmin, includeArchived));
        assertThat(semanticIds)
                .as("semantic/fts ACL parity for user=%s admin=%s archived=%s",
                        userId, isOrgAdmin, includeArchived)
                .isEqualTo(ftsIds);
    }

    // ---------------- helpers ----------------

    /** Creates a skill + current version whose indexed text contains {@link #TOKEN}, FTS-indexed. */
    private SkillEntity matchableSkill(String slug, String visibility, String status, UUID createdBy) {
        SkillVersionEntity version = new SkillVersionEntity();
        // version created after the skill so we can set current_version_id; two-step below.
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

        version.setSkillId(skill.getId());
        version.setVersionNumber(1);
        version.setCanonicalInstructions(TOKEN + " instructions for " + slug);
        version = skillVersionRepository.saveAndFlush(version);

        skill.setCurrentVersionId(version.getId());
        skill = skillRepository.saveAndFlush(skill);

        // Build the FTS search_vector so the skill is matchable by TOKEN.
        skillRepository.rebuildSearchVector(skill.getId(), "", TOKEN + " instructions for " + slug);
        return skill;
    }

    /** A matchable skill that also carries an embedding row, so it is semantic-rankable. */
    private SkillEntity embeddedSkill(String slug, String visibility, String status, UUID createdBy) {
        SkillEntity skill = matchableSkill(slug, visibility, status, createdBy);
        upsertEmbedding(skill.getId(), skill.getOrganizationId(), TOKEN + " " + slug);
        return skill;
    }

    /** A matchable, embedded skill in a different organization (ACL/org-scoping negative case). */
    private SkillEntity embeddedForeignOrgSkill() {
        OrganizationEntity other = organizationRepository.findBySlug("sem-other-org")
                .orElseGet(() -> {
                    var o = new OrganizationEntity();
                    o.setName("Other Org");
                    o.setSlug("sem-other-org");
                    return organizationRepository.saveAndFlush(o);
                });
        UserEntity otherUser = createUser(other.getId(), "sem-other-user", "sem-other@test.com");
        TeamEntity otherTeam = createTeam(other.getId(), "Other", "sem-other-team", otherUser.getId());

        SkillEntity skill = new SkillEntity();
        skill.setOrganizationId(other.getId());
        skill.setOwnerTeamId(otherTeam.getId());
        skill.setName(TOKEN + " foreign");
        skill.setSlug("sem-foreign");
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
        skill = skillRepository.saveAndFlush(skill);

        skillRepository.rebuildSearchVector(skill.getId(), "", TOKEN + " foreign instructions");
        upsertEmbedding(skill.getId(), skill.getOrganizationId(), TOKEN + " foreign");
        return skill;
    }

    private void upsertEmbedding(UUID skillId, UUID organizationId, String sourceText) {
        String literal = EmbeddingService.toVectorLiteral(embeddingProvider.embed(sourceText));
        embeddingRepository.upsert(skillId, organizationId, literal,
                embeddingProvider.modelId(), "hash-" + skillId);
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

    /** Mock JwtDecoder so repository-layer tests need no OIDC provider. */
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
