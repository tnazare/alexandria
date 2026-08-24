package com.alexandria.repository;

import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for V2 migration and repository layer.
 *
 * <p>Validates that Flyway V2 migration applies cleanly on a real PostgreSQL instance
 * and that all repository operations (CRUD, constraints, org scoping) work correctly.</p>
 *
 * <p>Validates: Requirements 2.1–2.10</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("V2 Migration and Repository Integration Tests")
class V2MigrationAndRepositoryIntegrationTest {

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
    }

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    private OrganizationEntity orgA;
    private OrganizationEntity orgB;

    @BeforeEach
    void setUp() {
        // Clean up in reverse dependency order
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();
        userRepository.deleteAll();

        // Create two organizations for org scoping tests
        orgA = organizationRepository.findBySlug("org-a-test")
                .orElseGet(() -> {
                    var org = new OrganizationEntity();
                    org.setName("Organization A");
                    org.setSlug("org-a-test");
                    return organizationRepository.saveAndFlush(org);
                });

        orgB = organizationRepository.findBySlug("org-b-test")
                .orElseGet(() -> {
                    var org = new OrganizationEntity();
                    org.setName("Organization B");
                    org.setSlug("org-b-test");
                    return organizationRepository.saveAndFlush(org);
                });
    }

    @Nested
    @DisplayName("V2 Migration")
    class MigrationTests {

        @Test
        @DisplayName("V2 migration applies cleanly - tables exist and accept inserts")
        void migrationAppliesCleanly() {
            // The fact that context loads with ddl-auto=validate proves
            // migration applied and schema matches JPA entities.
            // Additionally verify we can insert into all V2 tables.
            var user = createUser(orgA.getId(), "sub-migration-test", "migration@test.com");
            assertThat(user.getId()).isNotNull();

            var team = createTeam(orgA.getId(), "Migration Test Team", "migration-test-team", user.getId());
            assertThat(team.getId()).isNotNull();

            var member = createTeamMember(team.getId(), user.getId(), "owner");
            assertThat(member.getId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("UserRepository CRUD")
    class UserRepositoryTests {

        @Test
        @DisplayName("create user and retrieve by ID")
        void createAndFindUser() {
            var user = createUser(orgA.getId(), "subject-1", "user1@example.com");

            var found = userRepository.findById(user.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getExternalSubject()).isEqualTo("subject-1");
            assertThat(found.get().getEmail()).isEqualTo("user1@example.com");
            assertThat(found.get().getRole()).isEqualTo("Skill_Consumer");
            assertThat(found.get().isEnabled()).isTrue();
            assertThat(found.get().getOrganizationId()).isEqualTo(orgA.getId());
            assertThat(found.get().getCreatedAt()).isNotNull();
            assertThat(found.get().getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("find user by org and subject")
        void findByOrgAndSubject() {
            createUser(orgA.getId(), "unique-subject", "user@example.com");

            var found = userRepository.findByOrganizationIdAndExternalSubject(
                    orgA.getId(), "unique-subject");
            assertThat(found).isPresent();
            assertThat(found.get().getEmail()).isEqualTo("user@example.com");

            // Same subject in different org should not be found
            var notFound = userRepository.findByOrganizationIdAndExternalSubject(
                    orgB.getId(), "unique-subject");
            assertThat(notFound).isEmpty();
        }

        @Test
        @DisplayName("unique constraint on (organization_id, external_subject)")
        void uniqueConstraintEnforcement() {
            createUser(orgA.getId(), "dup-subject", "first@example.com");

            assertThatThrownBy(() ->
                createUser(orgA.getId(), "dup-subject", "second@example.com")
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same subject in different org is allowed")
        void sameSubjectDifferentOrgAllowed() {
            createUser(orgA.getId(), "shared-subject", "userA@example.com");
            var userB = createUser(orgB.getId(), "shared-subject", "userB@example.com");

            assertThat(userB.getId()).isNotNull();
        }

        @Test
        @DisplayName("find users by organization ID (paginated)")
        void findByOrganizationId() {
            createUser(orgA.getId(), "user-1", "u1@example.com");
            createUser(orgA.getId(), "user-2", "u2@example.com");
            createUser(orgB.getId(), "user-3", "u3@example.com");

            var page = userRepository.findByOrganizationId(orgA.getId(), PageRequest.of(0, 10));
            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getContent()).allMatch(u -> u.getOrganizationId().equals(orgA.getId()));
        }

        @Test
        @DisplayName("find user by ID and organization ID")
        void findByIdAndOrganizationId() {
            var user = createUser(orgA.getId(), "findable", "findme@example.com");

            var found = userRepository.findByIdAndOrganizationId(user.getId(), orgA.getId());
            assertThat(found).isPresent();

            // Same user ID but wrong org should not be found
            var notFound = userRepository.findByIdAndOrganizationId(user.getId(), orgB.getId());
            assertThat(notFound).isEmpty();
        }
    }

    @Nested
    @DisplayName("TeamRepository CRUD")
    class TeamRepositoryTests {

        private UserEntity teamCreator;

        @BeforeEach
        void setUpTeamCreator() {
            teamCreator = createUser(orgA.getId(), "team-creator", "creator@example.com");
        }

        @Test
        @DisplayName("create team and retrieve by ID")
        void createAndFindTeam() {
            var team = createTeam(orgA.getId(), "Engineering", "engineering", teamCreator.getId());

            var found = teamRepository.findById(team.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Engineering");
            assertThat(found.get().getSlug()).isEqualTo("engineering");
            assertThat(found.get().getOrganizationId()).isEqualTo(orgA.getId());
            assertThat(found.get().getCreatedBy()).isEqualTo(teamCreator.getId());
            assertThat(found.get().getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("find teams by organization ID")
        void findByOrganizationId() {
            createTeam(orgA.getId(), "Team A1", "team-a1", teamCreator.getId());
            createTeam(orgA.getId(), "Team A2", "team-a2", teamCreator.getId());

            var creatorB = createUser(orgB.getId(), "creator-b", "creatorb@example.com");
            createTeam(orgB.getId(), "Team B1", "team-b1", creatorB.getId());

            var page = teamRepository.findByOrganizationId(orgA.getId(), PageRequest.of(0, 10));
            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getContent()).allMatch(t -> t.getOrganizationId().equals(orgA.getId()));
        }

        @Test
        @DisplayName("find team by ID and organization ID")
        void findByIdAndOrganizationId() {
            var team = createTeam(orgA.getId(), "Findable Team", "findable-team", teamCreator.getId());

            var found = teamRepository.findByIdAndOrganizationId(team.getId(), orgA.getId());
            assertThat(found).isPresent();

            var notFound = teamRepository.findByIdAndOrganizationId(team.getId(), orgB.getId());
            assertThat(notFound).isEmpty();
        }

        @Test
        @DisplayName("slug uniqueness within organization")
        void slugUniquenessWithinOrg() {
            createTeam(orgA.getId(), "Team One", "same-slug", teamCreator.getId());

            assertThatThrownBy(() ->
                createTeam(orgA.getId(), "Team Two", "same-slug", teamCreator.getId())
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same slug in different org is allowed")
        void sameSlugDifferentOrgAllowed() {
            createTeam(orgA.getId(), "Team", "shared-slug", teamCreator.getId());

            var creatorB = createUser(orgB.getId(), "creator-b2", "creatorb2@example.com");
            var teamB = createTeam(orgB.getId(), "Team", "shared-slug", creatorB.getId());

            assertThat(teamB.getId()).isNotNull();
        }

        @Test
        @DisplayName("existsByOrganizationIdAndSlug returns correct result")
        void existsByOrgAndSlug() {
            createTeam(orgA.getId(), "Existing Team", "existing-slug", teamCreator.getId());

            assertThat(teamRepository.existsByOrganizationIdAndSlug(orgA.getId(), "existing-slug")).isTrue();
            assertThat(teamRepository.existsByOrganizationIdAndSlug(orgA.getId(), "non-existing")).isFalse();
            assertThat(teamRepository.existsByOrganizationIdAndSlug(orgB.getId(), "existing-slug")).isFalse();
        }
    }

    @Nested
    @DisplayName("TeamMemberRepository")
    class TeamMemberRepositoryTests {

        private UserEntity user1;
        private UserEntity user2;
        private TeamEntity team;

        @BeforeEach
        void setUpTeamAndUsers() {
            user1 = createUser(orgA.getId(), "member-user-1", "member1@example.com");
            user2 = createUser(orgA.getId(), "member-user-2", "member2@example.com");
            team = createTeam(orgA.getId(), "Members Team", "members-team", user1.getId());
        }

        @Test
        @DisplayName("add member to team")
        void addMember() {
            var member = createTeamMember(team.getId(), user1.getId(), "owner");

            assertThat(member.getId()).isNotNull();
            assertThat(member.getTeamId()).isEqualTo(team.getId());
            assertThat(member.getUserId()).isEqualTo(user1.getId());
            assertThat(member.getTeamRole()).isEqualTo("owner");
            assertThat(member.getJoinedAt()).isNotNull();
        }

        @Test
        @DisplayName("unique constraint on (team_id, user_id)")
        void uniqueConstraintTeamUser() {
            createTeamMember(team.getId(), user1.getId(), "owner");

            assertThatThrownBy(() ->
                createTeamMember(team.getId(), user1.getId(), "member")
            ).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("same user in different teams is allowed")
        void sameUserDifferentTeamsAllowed() {
            var team2 = createTeam(orgA.getId(), "Second Team", "second-team", user1.getId());

            createTeamMember(team.getId(), user1.getId(), "owner");
            var member2 = createTeamMember(team2.getId(), user1.getId(), "member");

            assertThat(member2.getId()).isNotNull();
        }

        @Test
        @DisplayName("find members by team ID")
        void findByTeamId() {
            createTeamMember(team.getId(), user1.getId(), "owner");
            createTeamMember(team.getId(), user2.getId(), "member");

            var members = teamMemberRepository.findByTeamId(team.getId());
            assertThat(members).hasSize(2);
        }

        @Test
        @DisplayName("find member by team and user")
        void findByTeamIdAndUserId() {
            createTeamMember(team.getId(), user1.getId(), "owner");

            var found = teamMemberRepository.findByTeamIdAndUserId(team.getId(), user1.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getTeamRole()).isEqualTo("owner");

            var notFound = teamMemberRepository.findByTeamIdAndUserId(team.getId(), user2.getId());
            assertThat(notFound).isEmpty();
        }

        @Test
        @DisplayName("count members by team and role")
        void countByTeamIdAndTeamRole() {
            createTeamMember(team.getId(), user1.getId(), "owner");
            createTeamMember(team.getId(), user2.getId(), "member");

            assertThat(teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "owner")).isEqualTo(1);
            assertThat(teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "member")).isEqualTo(1);
            assertThat(teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "admin")).isZero();
        }
    }

    @Nested
    @DisplayName("Organization Scoping")
    class OrgScopingTests {

        @Test
        @DisplayName("user queries only return records for the specified org")
        void userOrgScoping() {
            createUser(orgA.getId(), "org-a-user", "orgA@example.com");
            createUser(orgB.getId(), "org-b-user", "orgB@example.com");

            var orgAUsers = userRepository.findByOrganizationId(orgA.getId(), PageRequest.of(0, 100));
            assertThat(orgAUsers.getContent()).allMatch(u -> u.getOrganizationId().equals(orgA.getId()));
            assertThat(orgAUsers.getContent()).noneMatch(u -> u.getOrganizationId().equals(orgB.getId()));

            var orgBUsers = userRepository.findByOrganizationId(orgB.getId(), PageRequest.of(0, 100));
            assertThat(orgBUsers.getContent()).allMatch(u -> u.getOrganizationId().equals(orgB.getId()));
            assertThat(orgBUsers.getContent()).noneMatch(u -> u.getOrganizationId().equals(orgA.getId()));
        }

        @Test
        @DisplayName("team queries only return records for the specified org")
        void teamOrgScoping() {
            var creatorA = createUser(orgA.getId(), "creator-scope-a", "creatorA@test.com");
            var creatorB = createUser(orgB.getId(), "creator-scope-b", "creatorB@test.com");
            createTeam(orgA.getId(), "Org A Team", "org-a-team", creatorA.getId());
            createTeam(orgB.getId(), "Org B Team", "org-b-team", creatorB.getId());

            var orgATeams = teamRepository.findByOrganizationId(orgA.getId(), PageRequest.of(0, 100));
            assertThat(orgATeams.getContent()).allMatch(t -> t.getOrganizationId().equals(orgA.getId()));
            assertThat(orgATeams.getContent()).noneMatch(t -> t.getOrganizationId().equals(orgB.getId()));
        }

        @Test
        @DisplayName("findByIdAndOrganizationId does not leak across orgs")
        void crossOrgIsolation() {
            var user = createUser(orgA.getId(), "isolated-user", "isolated@test.com");
            var team = createTeam(orgA.getId(), "Isolated Team", "isolated-team", user.getId());

            // Accessing with wrong org must return empty
            assertThat(userRepository.findByIdAndOrganizationId(user.getId(), orgB.getId())).isEmpty();
            assertThat(teamRepository.findByIdAndOrganizationId(team.getId(), orgB.getId())).isEmpty();
        }
    }

    // --- Helper methods ---

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
}
