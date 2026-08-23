package com.alexandria.team;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for slug generation in TeamServiceImpl.
 *
 * <p>Property 1: Slug generation is a deterministic, well-formed transformation —
 * for any valid team name (2-100 chars from [a-zA-Z0-9 \-_]), the generated slug
 * SHALL be lowercase, contain only [a-z0-9-], have no consecutive hyphens,
 * not start or end with a hyphen, and be non-empty. Names differing only in
 * case/whitespace-vs-hyphen/underscore-vs-hyphen produce the same slug.</p>
 *
 * <p>Validates: Requirements 5.9</p>
 */
@Tag("Feature: alexandria-first-step, Property 1: Slug generation is a deterministic, well-formed transformation")
class SlugGenerationPropertyTest {

    /**
     * Property: Slug is always lowercase and contains only [a-z0-9-].
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void slugContainsOnlyAllowedCharacters(@ForAll("validTeamNames") String name) {
        String slug = TeamServiceImpl.generateSlug(name);

        assertThat(slug).matches("^[a-z0-9\\-]*$");
    }

    /**
     * Property: Slug never contains consecutive hyphens.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void slugHasNoConsecutiveHyphens(@ForAll("validTeamNames") String name) {
        String slug = TeamServiceImpl.generateSlug(name);

        assertThat(slug).doesNotContain("--");
    }

    /**
     * Property: Slug does not start or end with a hyphen.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void slugDoesNotStartOrEndWithHyphen(@ForAll("validTeamNames") String name) {
        String slug = TeamServiceImpl.generateSlug(name);

        if (!slug.isEmpty()) {
            assertThat(slug.charAt(0)).isNotEqualTo('-');
            assertThat(slug.charAt(slug.length() - 1)).isNotEqualTo('-');
        }
    }

    /**
     * Property: Slug is non-empty for any valid team name containing at least one alphanumeric char.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void slugIsNonEmptyForNamesWithAlphanumericChars(@ForAll("validTeamNamesWithAlphanumeric") String name) {
        String slug = TeamServiceImpl.generateSlug(name);

        assertThat(slug).isNotEmpty();
    }

    /**
     * Property: Slug generation is deterministic — same input always produces same output.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void slugGenerationIsDeterministic(@ForAll("validTeamNames") String name) {
        String slug1 = TeamServiceImpl.generateSlug(name);
        String slug2 = TeamServiceImpl.generateSlug(name);

        assertThat(slug1).isEqualTo(slug2);
    }

    /**
     * Property: Names differing only in case produce the same slug.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void namesDifferingOnlyInCaseProduceSameSlug(@ForAll("validTeamNames") String name) {
        String slugFromOriginal = TeamServiceImpl.generateSlug(name);
        String slugFromUpper = TeamServiceImpl.generateSlug(name.toUpperCase());
        String slugFromLower = TeamServiceImpl.generateSlug(name.toLowerCase());

        assertThat(slugFromOriginal).isEqualTo(slugFromUpper);
        assertThat(slugFromOriginal).isEqualTo(slugFromLower);
    }

    /**
     * Property: Names differing only in whitespace-vs-hyphen produce the same slug.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void namesDifferingInWhitespaceVsHyphenProduceSameSlug(@ForAll("validTeamNames") String name) {
        String withSpaces = name.replace('-', ' ');
        String withHyphens = name.replace(' ', '-');

        String slugFromSpaces = TeamServiceImpl.generateSlug(withSpaces);
        String slugFromHyphens = TeamServiceImpl.generateSlug(withHyphens);

        assertThat(slugFromSpaces).isEqualTo(slugFromHyphens);
    }

    /**
     * Property: Names differing only in underscore-vs-hyphen produce the same slug.
     *
     * Validates: Requirements 5.9
     */
    @Property(tries = 100)
    void namesDifferingInUnderscoreVsHyphenProduceSameSlug(@ForAll("validTeamNames") String name) {
        String withUnderscores = name.replace('-', '_');
        String withHyphens = name.replace('_', '-');

        String slugFromUnderscores = TeamServiceImpl.generateSlug(withUnderscores);
        String slugFromHyphens = TeamServiceImpl.generateSlug(withHyphens);

        assertThat(slugFromUnderscores).isEqualTo(slugFromHyphens);
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> validTeamNames() {
        // Valid team names: 2-100 chars from [a-zA-Z0-9 \-_]
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_")
                .ofMinLength(2)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> validTeamNamesWithAlphanumeric() {
        // Valid team names that contain at least one alphanumeric character
        return validTeamNames()
                .filter(name -> name.chars().anyMatch(c ->
                        Character.isLetterOrDigit(c)));
    }
}
