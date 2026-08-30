package com.alexandria.config;

import com.alexandria.AlexandriaApplication;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
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
import org.springframework.data.domain.Pageable;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for user provisioning idempotency.
 *
 * <p>Validates that for any unique (organizationId, externalSubject) pair,
 * multiple sequential provisioning attempts result in exactly one user record.
 * The idempotency is guaranteed by the database unique constraint on
 * (organization_id, external_subject) and the find-or-create logic in
 * UserProvisioningFilter.</p>
 *
 * <p><b>Validates: Requirements 4.1, 4.2</b></p>
 */
@Tag("property-test")
class UserProvisioningIdempotencyPropertyTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("alexandria_test")
                    .withUsername("test")
                    .withPassword("test");

    private static ConfigurableApplicationContext context;
    private static UserRepository userRepository;
    private static OrganizationRepository organizationRepository;

    private UUID testOrgId;

    @BeforeContainer
    static void startContext() {
        POSTGRES.start();

        // Set system properties to override application.yml with highest precedence
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

        userRepository = context.getBean(UserRepository.class);
        organizationRepository = context.getBean(OrganizationRepository.class);
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
        // Create a test organization for each property run
        var org = new OrganizationEntity();
        org.setName("Test Org " + UUID.randomUUID());
        org.setSlug("test-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        testOrgId = org.getId();
    }

    @AfterProperty
    void tearDown() {
        // Clean up users for this org, then the org itself
        userRepository.findByOrganizationId(testOrgId, Pageable.unpaged())
                .forEach(userRepository::delete);
        organizationRepository.deleteById(testOrgId);
    }

    /**
     * Property: Multiple sequential provisioning calls for the same (orgId, subject)
     * result in exactly one user record in the database.
     *
     * <p>This simulates the find-or-create logic from UserProvisioningFilter:
     * look up user by (orgId, subject), create if not found.</p>
     *
     * <b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 100)
    void multipleProvisioningCallsResultInExactlyOneUser(
            @ForAll("subjects") String subject,
            @ForAll("emails") String email,
            @ForAll("provisioningAttempts") int attempts) {

        // Simulate multiple sequential provisioning calls (2 to 5)
        UUID firstUserId = null;
        for (int i = 0; i < attempts; i++) {
            UserEntity user = provisionUser(testOrgId, subject, email);
            if (firstUserId == null) {
                firstUserId = user.getId();
            }
            // All provisioning calls must return the same user
            assertThat(user.getId()).isEqualTo(firstUserId);
        }

        // Verify exactly one record exists for this (orgId, subject) pair
        var found = userRepository.findByOrganizationIdAndExternalSubject(testOrgId, subject);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(firstUserId);

        // Verify the user's immutable identity fields are unchanged
        assertThat(found.get().getExternalSubject()).isEqualTo(subject);
        assertThat(found.get().getOrganizationId()).isEqualTo(testOrgId);
        assertThat(found.get().getCreatedAt()).isNotNull();

        // Count total users with this subject in this org — must be exactly 1
        long count = userRepository.findByOrganizationId(testOrgId, Pageable.unpaged())
                .getContent().stream()
                .filter(u -> u.getExternalSubject().equals(subject))
                .count();
        assertThat(count).isEqualTo(1);

        // Clean up for next iteration to avoid cross-contamination
        userRepository.delete(found.get());
    }

    /**
     * Simulates the find-or-create provisioning logic from UserProvisioningFilter.
     * Looks up user by (orgId, subject), creates if not found.
     */
    private UserEntity provisionUser(UUID orgId, String subject, String email) {
        return userRepository.findByOrganizationIdAndExternalSubject(orgId, subject)
                .orElseGet(() -> {
                    var user = new UserEntity();
                    user.setOrganizationId(orgId);
                    user.setExternalSubject(subject);
                    user.setEmail(email);
                    user.setRole(Role.Skill_Consumer.name());
                    user.setEnabled(true);
                    return userRepository.save(user);
                });
    }

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
    Arbitrary<Integer> provisioningAttempts() {
        return Arbitraries.integers().between(2, 5);
    }
}
