package com.alexandria.config;

import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

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
@JqwikSpringSupport
@SpringBootTest
@ActiveProfiles("test")
@Tag("Feature: alexandria-first-step, Property 4: User provisioning idempotency")
class UserProvisioningIdempotencyPropertyTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID testOrgId;

    @BeforeProperty
    void setUp() {
        // Create a test organization for each property run
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Test Org " + UUID.randomUUID());
        org.setSlug("test-org-" + UUID.randomUUID());
        org = organizationRepository.save(org);
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
        var localPart = Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(20)
                .alpha()
                .numeric()
                .withChars('.', '_', '-');
        var domain = Arbitraries.strings()
                .ofMinLength(2)
                .ofMaxLength(15)
                .alpha();
        var tld = Arbitraries.of("com", "org", "net", "io", "dev");
        return Combinators.combine(localPart, domain, tld)
                .as((local, dom, t) -> local + "@" + dom + "." + t);
    }

    @Provide
    Arbitrary<Integer> provisioningAttempts() {
        return Arbitraries.integers().between(2, 5);
    }
}
