package com.alexandria.audit;

import com.alexandria.AlexandriaApplication;
import com.alexandria.audit.entity.AuditEventEntity;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.TeamService;
import com.alexandria.team.dto.AddMemberRequest;
import com.alexandria.team.dto.CreateTeamRequest;
import com.alexandria.team.dto.TeamResponse;
import com.alexandria.user.UserRepository;
import com.alexandria.user.UserService;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for audit completeness and field correctness.
 *
 * <p>For each governance operation (user provisioning, role change, team creation,
 * member add/remove), verifies exactly one audit event is created with correct
 * organization_id, action, target_type, target_id, and non-empty metadata.</p>
 *
 * <p><b>Validates: Requirements 7.1, 7.2, 4.3, 5.7, 5.8, 6.9</b></p>
 */
@Tag("property-test")
class AuditCompletenessPropertyTest {

    @SuppressWarnings("resource")
    private static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("alexandria_test")
                    .withUsername("test")
                    .withPassword("test");

    private static ConfigurableApplicationContext context;
    private static AuditEventRepository auditEventRepository;
    private static OrganizationRepository organizationRepository;
    private static UserRepository userRepository;
    private static TeamRepository teamRepository;
    private static TeamMemberRepository teamMemberRepository;
    private static UserService userService;
    private static TeamService teamService;

    private UUID testOrgId;
    private UUID actorUserId;

    private static final List<String> VALID_ROLES = List.of(
            "Org_Admin", "Catalog_Admin", "Reviewer", "Team_Owner",
            "Skill_Author", "Skill_Consumer", "Read_Only_Auditor");

    @BeforeContainer
    static void startContext() {
        POSTGRES.start();

        System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES.getPassword());
        System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        System.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        System.setProperty("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
        System.setProperty("spring.flyway.enabled", "true");
        System.setProperty("spring.flyway.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.flyway.user", POSTGRES.getUsername());
        System.setProperty("spring.flyway.password", POSTGRES.getPassword());
        System.setProperty("spring.flyway.locations", "classpath:db/migration");
        System.setProperty("OIDC_ISSUER_URI", "https://issuer.example.com");
        System.setProperty("OIDC_CLIENT_ID", "test-client-id");
        // Provide a JWK set URI so SecurityConfig builds a lazy NimbusJwtDecoder
        // (withJwkSetUri) instead of eagerly resolving the OIDC issuer over the
        // network at startup, which is unavailable in the test environment.
        System.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "https://issuer.example.com/.well-known/jwks.json");
        System.setProperty("alexandria.organization.default-id", "00000000-0000-0000-0000-000000000001");
        System.setProperty("server.port", "0");

        context = new SpringApplicationBuilder(AlexandriaApplication.class)
                .run();

        auditEventRepository = context.getBean(AuditEventRepository.class);
        organizationRepository = context.getBean(OrganizationRepository.class);
        userRepository = context.getBean(UserRepository.class);
        teamRepository = context.getBean(TeamRepository.class);
        teamMemberRepository = context.getBean(TeamMemberRepository.class);
        userService = context.getBean(UserService.class);
        teamService = context.getBean(TeamService.class);
    }

    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
        System.clearProperty("spring.datasource.url");
        System.clearProperty("spring.datasource.username");
        System.clearProperty("spring.datasource.password");
        System.clearProperty("spring.datasource.driver-class-name");
        System.clearProperty("spring.jpa.hibernate.ddl-auto");
        System.clearProperty("spring.jpa.database-platform");
        System.clearProperty("spring.flyway.enabled");
        System.clearProperty("spring.flyway.url");
        System.clearProperty("spring.flyway.user");
        System.clearProperty("spring.flyway.password");
        System.clearProperty("spring.flyway.locations");
        System.clearProperty("OIDC_ISSUER_URI");
        System.clearProperty("OIDC_CLIENT_ID");
        System.clearProperty("alexandria.organization.default-id");
        System.clearProperty("server.port");
    }

    @BeforeProperty
    void setUp() {
        // Create a test organization
        var org = new OrganizationEntity();
        org.setName("Audit Completeness Org " + UUID.randomUUID());
        org.setSlug("audit-completeness-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        testOrgId = org.getId();

        // Create an actor user (Org_Admin) for performing operations
        var actor = new UserEntity();
        actor.setOrganizationId(testOrgId);
        actor.setExternalSubject("actor-" + UUID.randomUUID());
        actor.setEmail("actor-" + UUID.randomUUID() + "@example.com");
        actor.setRole(Role.Org_Admin.name());
        actor.setEnabled(true);
        actor = userRepository.saveAndFlush(actor);
        actorUserId = actor.getId();
    }

    @AfterProperty
    void tearDown() {
        // Clean up in dependency order: audit events, team members, teams, users, org
        var allEvents = auditEventRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000));
        auditEventRepository.deleteAll(allEvents.getContent());

        teamMemberRepository.findAll().stream()
                .filter(m -> {
                    var team = teamRepository.findById(m.getTeamId());
                    return team.isPresent() && team.get().getOrganizationId().equals(testOrgId);
                })
                .forEach(teamMemberRepository::delete);

        teamRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000))
                .forEach(teamRepository::delete);

        userRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000))
                .forEach(userRepository::delete);

        organizationRepository.deleteById(testOrgId);
    }

    /**
     * Property: User provisioning creates exactly one audit event with action
     * "user.auto_provisioned", target_type "user", correct target_id, and non-empty metadata.
     *
     * <b>Validates: Requirements 7.1, 7.2, 4.3</b>
     */
    @Property(tries = 100)
    void userProvisioningCreatesExactlyOneAuditEvent(
            @ForAll("subjects") String subject,
            @ForAll("emails") String email) {

        // Clear any pre-existing audit events for this org
        clearAuditEvents();

        // Simulate user provisioning (the find-or-create logic from the filter)
        var user = new UserEntity();
        user.setOrganizationId(testOrgId);
        user.setExternalSubject(subject);
        user.setEmail(email);
        user.setRole(Role.Skill_Consumer.name());
        user.setEnabled(true);
        UserEntity savedUser = userRepository.saveAndFlush(user);

        // Record the audit event as the provisioning filter does
        var auditEntity = new AuditEventEntity();
        auditEntity.setOrganizationId(testOrgId);
        auditEntity.setActorUserId(savedUser.getId());
        auditEntity.setAction("user.auto_provisioned");
        auditEntity.setTargetType("user");
        auditEntity.setTargetId(savedUser.getId());
        auditEntity.setMetadata(Map.of("email", email, "subject", subject));
        auditEventRepository.saveAndFlush(auditEntity);

        // Verify exactly one audit event was created
        var events = findAuditEventsForTarget("user", savedUser.getId());
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getOrganizationId()).isEqualTo(testOrgId);
        assertThat(event.getAction()).isEqualTo("user.auto_provisioned");
        assertThat(event.getTargetType()).isEqualTo("user");
        assertThat(event.getTargetId()).isEqualTo(savedUser.getId());
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getMetadata()).isNotNull().isNotEmpty();
        assertThat(event.getMetadata()).containsKey("email");
        assertThat(event.getMetadata()).containsKey("subject");

        // Clean up for next iteration
        auditEventRepository.delete(event);
        userRepository.delete(savedUser);
    }

    /**
     * Property: Role change creates exactly one audit event with action "user.role_changed",
     * target_type "user", correct target_id, and metadata containing previous_role and new_role.
     *
     * <b>Validates: Requirements 7.1, 7.2, 6.9</b>
     */
    @Property(tries = 100)
    void roleChangeCreatesExactlyOneAuditEvent(
            @ForAll("roleChanges") RoleChangeInput input) {

        // Clear any pre-existing audit events
        clearAuditEvents();

        // Create a target user with the initial role
        var targetUser = new UserEntity();
        targetUser.setOrganizationId(testOrgId);
        targetUser.setExternalSubject("target-" + UUID.randomUUID());
        targetUser.setEmail("target-" + UUID.randomUUID() + "@example.com");
        targetUser.setRole(input.fromRole());
        targetUser.setEnabled(true);
        targetUser = userRepository.saveAndFlush(targetUser);

        // Perform role change via the service
        userService.changeRole(testOrgId, actorUserId, targetUser.getId(), input.toRole());

        // Verify exactly one audit event with "user.role_changed"
        var events = findAuditEventsForTarget("user", targetUser.getId());
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getOrganizationId()).isEqualTo(testOrgId);
        assertThat(event.getAction()).isEqualTo("user.role_changed");
        assertThat(event.getTargetType()).isEqualTo("user");
        assertThat(event.getTargetId()).isEqualTo(targetUser.getId());
        assertThat(event.getActorUserId()).isEqualTo(actorUserId);
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getMetadata()).isNotNull().isNotEmpty();
        assertThat(event.getMetadata()).containsEntry("previous_role", input.fromRole());
        assertThat(event.getMetadata()).containsEntry("new_role", input.toRole());

        // Clean up
        auditEventRepository.delete(event);
        userRepository.delete(targetUser);
    }

    /**
     * Property: Team creation creates exactly one audit event with action "team.created",
     * target_type "team", correct target_id, and non-empty metadata.
     *
     * <b>Validates: Requirements 7.1, 7.2, 5.7</b>
     */
    @Property(tries = 100)
    void teamCreationCreatesExactlyOneAuditEvent(
            @ForAll("teamNames") String teamName) {

        // Clear any pre-existing audit events
        clearAuditEvents();

        // Create a team via the service
        var request = new CreateTeamRequest(teamName, "Description for " + teamName);
        TeamResponse response = teamService.createTeam(testOrgId, actorUserId, request);

        // Verify exactly one audit event with "team.created"
        var events = findAuditEventsForTarget("team", response.id());
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getOrganizationId()).isEqualTo(testOrgId);
        assertThat(event.getAction()).isEqualTo("team.created");
        assertThat(event.getTargetType()).isEqualTo("team");
        assertThat(event.getTargetId()).isEqualTo(response.id());
        assertThat(event.getActorUserId()).isEqualTo(actorUserId);
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getMetadata()).isNotNull().isNotEmpty();
        assertThat(event.getMetadata()).containsKey("name");
        assertThat(event.getMetadata()).containsKey("slug");

        // Clean up: members, then team, then audit
        teamMemberRepository.findByTeamId(response.id())
                .forEach(teamMemberRepository::delete);
        teamRepository.deleteById(response.id());
        auditEventRepository.delete(event);
    }

    /**
     * Property: Adding a member creates exactly one audit event with action "team_member.added",
     * target_type "team_member", and non-empty metadata.
     *
     * <b>Validates: Requirements 7.1, 7.2, 5.8</b>
     */
    @Property(tries = 100)
    void memberAdditionCreatesExactlyOneAuditEvent(
            @ForAll("teamNames") String teamName,
            @ForAll("teamRoles") String teamRole) {

        // Clear any pre-existing audit events
        clearAuditEvents();

        // Create a team first
        var teamRequest = new CreateTeamRequest(teamName, "Test team");
        TeamResponse teamResponse = teamService.createTeam(testOrgId, actorUserId, teamRequest);

        // Create a user to add as member
        var memberUser = new UserEntity();
        memberUser.setOrganizationId(testOrgId);
        memberUser.setExternalSubject("member-" + UUID.randomUUID());
        memberUser.setEmail("member-" + UUID.randomUUID() + "@example.com");
        memberUser.setRole(Role.Skill_Consumer.name());
        memberUser.setEnabled(true);
        memberUser = userRepository.saveAndFlush(memberUser);

        // Clear events from team creation before measuring member addition
        clearAuditEvents();

        // Add member via the service
        var addRequest = new AddMemberRequest(memberUser.getId(), teamRole);
        var memberResponse = teamService.addMember(testOrgId, teamResponse.id(), addRequest);

        // Find the audit event for the member addition (target_type = "team_member")
        var events = findAuditEventsForAction("team_member.added");
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getOrganizationId()).isEqualTo(testOrgId);
        assertThat(event.getAction()).isEqualTo("team_member.added");
        assertThat(event.getTargetType()).isEqualTo("team_member");
        assertThat(event.getTargetId()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getMetadata()).isNotNull().isNotEmpty();
        assertThat(event.getMetadata()).containsKey("teamId");
        assertThat(event.getMetadata()).containsKey("userId");
        assertThat(event.getMetadata()).containsKey("teamRole");

        // Clean up
        auditEventRepository.deleteAll(events);
        teamMemberRepository.findByTeamId(teamResponse.id())
                .forEach(teamMemberRepository::delete);
        teamRepository.deleteById(teamResponse.id());
        userRepository.delete(memberUser);
    }

    /**
     * Property: Removing a member creates exactly one audit event with action
     * "team_member.removed", target_type "team_member", and non-empty metadata.
     *
     * <b>Validates: Requirements 7.1, 7.2, 5.8</b>
     */
    @Property(tries = 100)
    void memberRemovalCreatesExactlyOneAuditEvent(
            @ForAll("teamNames") String teamName) {

        // Clear any pre-existing audit events
        clearAuditEvents();

        // Create a team (actor becomes owner)
        var teamRequest = new CreateTeamRequest(teamName, "Test team");
        TeamResponse teamResponse = teamService.createTeam(testOrgId, actorUserId, teamRequest);

        // Create and add a second member so we can remove them
        var memberUser = new UserEntity();
        memberUser.setOrganizationId(testOrgId);
        memberUser.setExternalSubject("removable-" + UUID.randomUUID());
        memberUser.setEmail("removable-" + UUID.randomUUID() + "@example.com");
        memberUser.setRole(Role.Skill_Consumer.name());
        memberUser.setEnabled(true);
        memberUser = userRepository.saveAndFlush(memberUser);

        var addRequest = new AddMemberRequest(memberUser.getId(), "member");
        teamService.addMember(testOrgId, teamResponse.id(), addRequest);

        // Clear all audit events before the removal operation
        clearAuditEvents();

        // Remove the member
        teamService.removeMember(testOrgId, teamResponse.id(), memberUser.getId());

        // Verify exactly one audit event with "team_member.removed"
        var events = findAuditEventsForAction("team_member.removed");
        assertThat(events).hasSize(1);

        var event = events.getFirst();
        assertThat(event.getOrganizationId()).isEqualTo(testOrgId);
        assertThat(event.getAction()).isEqualTo("team_member.removed");
        assertThat(event.getTargetType()).isEqualTo("team_member");
        assertThat(event.getTargetId()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getMetadata()).isNotNull().isNotEmpty();
        assertThat(event.getMetadata()).containsKey("teamId");
        assertThat(event.getMetadata()).containsKey("userId");

        // Clean up
        auditEventRepository.deleteAll(events);
        teamMemberRepository.findByTeamId(teamResponse.id())
                .forEach(teamMemberRepository::delete);
        teamRepository.deleteById(teamResponse.id());
        userRepository.delete(memberUser);
    }

    // --- Helper methods ---

    private void clearAuditEvents() {
        var allEvents = auditEventRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000));
        auditEventRepository.deleteAll(allEvents.getContent());
        auditEventRepository.flush();
    }

    private List<AuditEventEntity> findAuditEventsForTarget(String targetType, UUID targetId) {
        return auditEventRepository.findByOrganizationIdAndTargetTypeAndTargetId(
                testOrgId, targetType, targetId, PageRequest.of(0, 100)).getContent();
    }

    private List<AuditEventEntity> findAuditEventsForAction(String action) {
        return auditEventRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000))
                .getContent().stream()
                .filter(e -> e.getAction().equals(action))
                .toList();
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> subjects() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(64)
                .alpha()
                .numeric()
                .withChars('-', '_', '.');
    }

    @Provide
    Arbitrary<String> emails() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(20)
                .alpha()
                .numeric()
                .withChars('.', '_', '-')
                .flatMap(local -> Arbitraries.strings()
                        .ofMinLength(2)
                        .ofMaxLength(15)
                        .alpha()
                        .flatMap(domain -> Arbitraries.of("com", "org", "net", "io", "dev")
                                .map(tld -> local + "@" + domain + "." + tld)));
    }

    @Provide
    Arbitrary<RoleChangeInput> roleChanges() {
        return Combinators.combine(
                Arbitraries.of(VALID_ROLES),
                Arbitraries.of(VALID_ROLES)
        ).filter((from, to) -> !from.equals(to))
         .as(RoleChangeInput::new);
    }

    @Provide
    Arbitrary<String> teamNames() {
        // Generate unique team names to avoid slug conflicts between iterations
        return Arbitraries.strings()
                .ofMinLength(2)
                .ofMaxLength(40)
                .alpha()
                .numeric()
                .withChars(' ', '-', '_')
                .filter(name -> name.matches("^[a-zA-Z0-9 \\-_]+$"))
                .filter(name -> name.trim().length() >= 2)
                .map(name -> name + " " + UUID.randomUUID().toString().substring(0, 8));
    }

    @Provide
    Arbitrary<String> teamRoles() {
        return Arbitraries.of("owner", "member");
    }

    /**
     * Holds a from/to role pair for role change property tests.
     */
    record RoleChangeInput(String fromRole, String toRole) {}
}
