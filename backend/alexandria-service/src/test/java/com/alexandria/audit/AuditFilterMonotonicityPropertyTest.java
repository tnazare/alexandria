package com.alexandria.audit;

import com.alexandria.AlexandriaApplication;
import com.alexandria.audit.entity.AuditEventEntity;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import jakarta.persistence.criteria.Predicate;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for audit event ordering and filter correctness.
 *
 * <p>Verifies two properties:</p>
 * <ol>
 *   <li><b>Filter monotonicity:</b> Adding any filter to a set of filters never increases
 *       the result set size.</li>
 *   <li><b>Ordering:</b> Results are always ordered by created_at descending (each element's
 *       timestamp &lt;= the previous).</li>
 * </ol>
 *
 * <p><b>Validates: Requirements 7.3, 7.4</b></p>
 */
@Tag("property-test")
class AuditFilterMonotonicityPropertyTest {

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

    private UUID testOrgId;
    private List<UUID> testUserIds;

    // Fixed sets for generating audit events consistently
    private static final List<String> ACTIONS = List.of(
            "user.auto_provisioned", "user.role_changed", "team.created",
            "team_member.added", "team_member.removed");
    private static final List<String> TARGET_TYPES = List.of("user", "team", "team_member");

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
        org.setName("Audit Test Org " + UUID.randomUUID());
        org.setSlug("audit-test-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        testOrgId = org.getId();

        // Create a few test users for actorUserId references
        testUserIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var user = new UserEntity();
            user.setOrganizationId(testOrgId);
            user.setExternalSubject("audit-test-subject-" + i + "-" + UUID.randomUUID());
            user.setEmail("audit-test-" + i + "@example.com");
            user.setRole(Role.Org_Admin.name());
            user.setEnabled(true);
            user = userRepository.saveAndFlush(user);
            testUserIds.add(user.getId());
        }
    }

    @AfterProperty
    void tearDown() {
        // Clean up audit events for this org
        var allEvents = auditEventRepository.findByOrganizationId(
                testOrgId, PageRequest.of(0, 10000));
        auditEventRepository.deleteAll(allEvents.getContent());

        // Clean up users and org
        for (UUID userId : testUserIds) {
            userRepository.deleteById(userId);
        }
        organizationRepository.deleteById(testOrgId);
    }

    /**
     * Property: Adding any filter to the current filter set never increases the result set size,
     * and results are always ordered by created_at descending.
     *
     * <p>Strategy: Generate a set of audit events, insert them, then verify that querying with
     * no filters returns all events in descending order, and that each additional filter applied
     * reduces or maintains (never increases) the result count.</p>
     *
     * <b>Validates: Requirements 7.3, 7.4</b>
     */
    @Property(tries = 100)
    void addingFilterNeverIncreasesResultSetAndResultsAreOrdered(
            @ForAll("auditEventSets") List<AuditEventData> eventDataList,
            @ForAll("filterCombinations") FilterCombo filterCombo) {

        // Insert generated audit events
        Instant baseTime = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < eventDataList.size(); i++) {
            AuditEventData data = eventDataList.get(i);
            var event = new AuditEventEntity();
            event.setOrganizationId(testOrgId);
            event.setActorUserId(testUserIds.get(data.actorIndex() % testUserIds.size()));
            event.setAction(data.action());
            event.setTargetType(data.targetType());
            event.setTargetId(data.targetId());
            event.setMetadata(Map.of("test", "value"));
            event.setCreatedAt(baseTime.plus(i, ChronoUnit.SECONDS));
            auditEventRepository.saveAndFlush(event);
        }

        var pageable = PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Query with NO filters — baseline result set
        Specification<AuditEventEntity> baseSpec = buildSpec(testOrgId, null, null, null, null, null, null);
        Page<AuditEventEntity> baseResults = auditEventRepository.findAll(baseSpec, pageable);
        long baseCount = baseResults.getTotalElements();

        // Verify ordering: results are sorted by created_at descending
        assertDescendingOrder(baseResults.getContent());

        // Apply filters progressively and verify monotonicity
        long previousCount = baseCount;

        // Apply targetType filter
        if (filterCombo.useTargetType()) {
            String targetTypeFilter = filterCombo.targetType();
            Specification<AuditEventEntity> spec = buildSpec(
                    testOrgId, targetTypeFilter, null, null, null, null, null);
            Page<AuditEventEntity> results = auditEventRepository.findAll(spec, pageable);
            assertThat(results.getTotalElements())
                    .as("Adding targetType filter should not increase result count")
                    .isLessThanOrEqualTo(previousCount);
            assertDescendingOrder(results.getContent());
            previousCount = results.getTotalElements();

            // Apply action filter on top
            if (filterCombo.useAction()) {
                String actionFilter = filterCombo.action();
                Specification<AuditEventEntity> spec2 = buildSpec(
                        testOrgId, targetTypeFilter, null, null, actionFilter, null, null);
                Page<AuditEventEntity> results2 = auditEventRepository.findAll(spec2, pageable);
                assertThat(results2.getTotalElements())
                        .as("Adding action filter should not increase result count")
                        .isLessThanOrEqualTo(previousCount);
                assertDescendingOrder(results2.getContent());
                previousCount = results2.getTotalElements();

                // Apply actorUserId filter on top
                if (filterCombo.useActorUserId()) {
                    UUID actorFilter = testUserIds.get(filterCombo.actorIndex() % testUserIds.size());
                    Specification<AuditEventEntity> spec3 = buildSpec(
                            testOrgId, targetTypeFilter, null, actorFilter, actionFilter, null, null);
                    Page<AuditEventEntity> results3 = auditEventRepository.findAll(spec3, pageable);
                    assertThat(results3.getTotalElements())
                            .as("Adding actorUserId filter should not increase result count")
                            .isLessThanOrEqualTo(previousCount);
                    assertDescendingOrder(results3.getContent());
                    previousCount = results3.getTotalElements();
                }
            }
        } else if (filterCombo.useAction()) {
            String actionFilter = filterCombo.action();
            Specification<AuditEventEntity> spec = buildSpec(
                    testOrgId, null, null, null, actionFilter, null, null);
            Page<AuditEventEntity> results = auditEventRepository.findAll(spec, pageable);
            assertThat(results.getTotalElements())
                    .as("Adding action filter should not increase result count")
                    .isLessThanOrEqualTo(previousCount);
            assertDescendingOrder(results.getContent());
            previousCount = results.getTotalElements();

            // Apply date range filter on top
            if (filterCombo.useDateRange()) {
                Instant startDate = baseTime.plus(filterCombo.startOffset(), ChronoUnit.SECONDS);
                Instant endDate = baseTime.plus(filterCombo.endOffset(), ChronoUnit.SECONDS);
                if (startDate.isAfter(endDate)) {
                    // Swap if inverted
                    Instant tmp = startDate;
                    startDate = endDate;
                    endDate = tmp;
                }
                Specification<AuditEventEntity> spec2 = buildSpec(
                        testOrgId, null, null, null, actionFilter, startDate, endDate);
                Page<AuditEventEntity> results2 = auditEventRepository.findAll(spec2, pageable);
                assertThat(results2.getTotalElements())
                        .as("Adding date range filter should not increase result count")
                        .isLessThanOrEqualTo(previousCount);
                assertDescendingOrder(results2.getContent());
            }
        } else if (filterCombo.useActorUserId()) {
            UUID actorFilter = testUserIds.get(filterCombo.actorIndex() % testUserIds.size());
            Specification<AuditEventEntity> spec = buildSpec(
                    testOrgId, null, null, actorFilter, null, null, null);
            Page<AuditEventEntity> results = auditEventRepository.findAll(spec, pageable);
            assertThat(results.getTotalElements())
                    .as("Adding actorUserId filter should not increase result count")
                    .isLessThanOrEqualTo(previousCount);
            assertDescendingOrder(results.getContent());
            previousCount = results.getTotalElements();

            // Apply targetId filter on top
            if (filterCombo.useTargetId()) {
                UUID targetIdFilter = filterCombo.targetId();
                Specification<AuditEventEntity> spec2 = buildSpec(
                        testOrgId, null, targetIdFilter, actorFilter, null, null, null);
                Page<AuditEventEntity> results2 = auditEventRepository.findAll(spec2, pageable);
                assertThat(results2.getTotalElements())
                        .as("Adding targetId filter should not increase result count")
                        .isLessThanOrEqualTo(previousCount);
                assertDescendingOrder(results2.getContent());
            }
        } else if (filterCombo.useDateRange()) {
            Instant startDate = baseTime.plus(filterCombo.startOffset(), ChronoUnit.SECONDS);
            Instant endDate = baseTime.plus(filterCombo.endOffset(), ChronoUnit.SECONDS);
            if (startDate.isAfter(endDate)) {
                Instant tmp = startDate;
                startDate = endDate;
                endDate = tmp;
            }
            Specification<AuditEventEntity> spec = buildSpec(
                    testOrgId, null, null, null, null, startDate, endDate);
            Page<AuditEventEntity> results = auditEventRepository.findAll(spec, pageable);
            assertThat(results.getTotalElements())
                    .as("Adding date range filter should not increase result count")
                    .isLessThanOrEqualTo(previousCount);
            assertDescendingOrder(results.getContent());
        }

        // Final cleanup for next iteration
        var allEvents = auditEventRepository.findByOrganizationId(testOrgId, PageRequest.of(0, 10000));
        auditEventRepository.deleteAll(allEvents.getContent());
    }

    private void assertDescendingOrder(List<AuditEventEntity> events) {
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getCreatedAt())
                    .as("Event at index %d should have created_at <= event at index %d", i, i - 1)
                    .isBeforeOrEqualTo(events.get(i - 1).getCreatedAt());
        }
    }

    /**
     * Builds a JPA Specification mirroring the AuditResource filtering logic.
     */
    private Specification<AuditEventEntity> buildSpec(
            UUID orgId, String targetType, UUID targetId,
            UUID actorUserId, String action, Instant startDate, Instant endDate) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), orgId));

            if (targetType != null) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (targetId != null) {
                predicates.add(cb.equal(root.get("targetId"), targetId));
            }
            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // --- Generators ---

    @Provide
    Arbitrary<List<AuditEventData>> auditEventSets() {
        Arbitrary<AuditEventData> singleEvent = Combinators.combine(
                Arbitraries.integers().between(0, 2),       // actorIndex
                Arbitraries.of(ACTIONS),                     // action
                Arbitraries.of(TARGET_TYPES),                // targetType
                Arbitraries.create(UUID::randomUUID)         // targetId
        ).as(AuditEventData::new);

        return singleEvent.list().ofMinSize(3).ofMaxSize(15);
    }

    @Provide
    Arbitrary<FilterCombo> filterCombinations() {
        // jqwik Combinators.combine supports max 8 args, so split into two groups
        var firstHalf = Combinators.combine(
                Arbitraries.of(true, false),                          // useTargetType
                Arbitraries.of(TARGET_TYPES),                         // targetType
                Arbitraries.of(true, false),                          // useAction
                Arbitraries.of(ACTIONS),                              // action
                Arbitraries.of(true, false),                          // useActorUserId
                Arbitraries.integers().between(0, 2)                  // actorIndex
        ).as((a, b, c, d, e, f) -> new Object[]{a, b, c, d, e, f});

        var secondHalf = Combinators.combine(
                Arbitraries.of(true, false),                          // useDateRange
                Arbitraries.integers().between(0, 7),                 // startOffset (seconds)
                Arbitraries.integers().between(5, 15),                // endOffset (seconds)
                Arbitraries.of(true, false),                          // useTargetId
                Arbitraries.create(UUID::randomUUID)                  // targetId
        ).as((a, b, c, d, e) -> new Object[]{a, b, c, d, e});

        return Combinators.combine(firstHalf, secondHalf).as((first, second) ->
                new FilterCombo(
                        (boolean) first[0], (String) first[1],
                        (boolean) first[2], (String) first[3],
                        (boolean) first[4], (int) first[5],
                        (boolean) second[0], (int) second[1], (int) second[2],
                        (boolean) second[3], (UUID) second[4]
                ));
    }

    /**
     * Data holder for generated audit event parameters.
     */
    record AuditEventData(int actorIndex, String action, String targetType, UUID targetId) {}

    /**
     * Data holder for a generated filter combination.
     */
    record FilterCombo(
            boolean useTargetType, String targetType,
            boolean useAction, String action,
            boolean useActorUserId, int actorIndex,
            boolean useDateRange, int startOffset, int endOffset,
            boolean useTargetId, UUID targetId) {}
}
