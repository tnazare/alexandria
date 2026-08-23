package com.alexandria.config;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for email claim validation in UserProvisioningFilter.
 *
 * <p>Property 3: Email claim validation on every request — for any authenticated request
 * where the JWT email claim is missing, empty, or does not match a valid email format
 * (contains @ with a non-empty local part and domain), the system SHALL reject the request
 * with HTTP 403.</p>
 *
 * <p>Validates: Requirements 4.4, 4.7</p>
 */
@Tag("Feature: alexandria-first-step, Property 3: Email claim validation on every request")
class EmailClaimValidationPropertyTest {

    /**
     * Property: Null email is always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void nullEmailIsRejected() {
        assertThat(UserProvisioningFilter.isValidEmail(null)).isFalse();
    }

    /**
     * Property: Empty string email is always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void emptyEmailIsRejected() {
        assertThat(UserProvisioningFilter.isValidEmail("")).isFalse();
    }

    /**
     * Property: Blank strings (whitespace only) are always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void blankEmailIsRejected(@ForAll("blankStrings") String blankEmail) {
        assertThat(UserProvisioningFilter.isValidEmail(blankEmail)).isFalse();
    }

    /**
     * Property: Strings without an @ character are always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void emailWithoutAtSignIsRejected(@ForAll("stringsWithoutAt") String noAtEmail) {
        assertThat(UserProvisioningFilter.isValidEmail(noAtEmail)).isFalse();
    }

    /**
     * Property: Strings with empty local part (starting with @) are always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void emailWithEmptyLocalPartIsRejected(@ForAll("emptyLocalPartEmails") String email) {
        assertThat(UserProvisioningFilter.isValidEmail(email)).isFalse();
    }

    /**
     * Property: Strings with empty domain (ending with @) are always rejected.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void emailWithEmptyDomainIsRejected(@ForAll("emptyDomainEmails") String email) {
        assertThat(UserProvisioningFilter.isValidEmail(email)).isFalse();
    }

    /**
     * Property: Any well-formed email (non-empty local part + @ + non-empty domain) is accepted.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void wellFormedEmailIsAccepted(@ForAll("validEmails") String email) {
        assertThat(UserProvisioningFilter.isValidEmail(email)).isTrue();
    }

    /**
     * Property: For any generated invalid email format, isValidEmail returns false.
     * This exercises the full space of invalid emails: null-like, missing @, empty local, empty domain.
     *
     * Validates: Requirements 4.4, 4.7
     */
    @Property(tries = 100)
    void allInvalidEmailFormatsAreRejected(@ForAll("invalidEmails") String email) {
        assertThat(UserProvisioningFilter.isValidEmail(email)).isFalse();
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of(" ", "  ", "\t", "\n", " \t\n ", "   ");
    }

    @Provide
    Arbitrary<String> stringsWithoutAt() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(100)
                .filter(s -> !s.contains("@") && !s.isBlank());
    }

    @Provide
    Arbitrary<String> emptyLocalPartEmails() {
        // Emails that start with @ — empty local part
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !s.contains("@") && !s.isBlank())
                .map(domain -> "@" + domain);
    }

    @Provide
    Arbitrary<String> emptyDomainEmails() {
        // Emails that end with @ — empty domain
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !s.contains("@") && !s.isBlank())
                .map(local -> local + "@");
    }

    @Provide
    Arbitrary<String> validEmails() {
        // Generate emails with non-empty local part + @ + non-empty domain
        Arbitrary<String> localParts = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30);

        Arbitrary<String> domains = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30);

        return Combinators.combine(localParts, domains)
                .as((local, domain) -> local + "@" + domain);
    }

    @Provide
    Arbitrary<String> invalidEmails() {
        // Mix of all invalid email patterns
        Arbitrary<String> emptyStrings = Arbitraries.of("", " ", "  ", "\t", "\n");
        Arbitrary<String> noAt = stringsWithoutAt();
        Arbitrary<String> emptyLocal = emptyLocalPartEmails();
        Arbitrary<String> emptyDomain = emptyDomainEmails();

        return Arbitraries.oneOf(emptyStrings, noAt, emptyLocal, emptyDomain);
    }
}
