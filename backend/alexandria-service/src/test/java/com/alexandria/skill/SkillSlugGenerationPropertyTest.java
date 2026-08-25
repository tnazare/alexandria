package com.alexandria.skill;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for slug generation in SkillSlugGenerator.
 *
 * <p><b>Property 1: Slug generation produces well-formed, unique identifiers</b> —
 * for any valid skill name (3–200 chars from [a-zA-Z0-9 \-_]), the generated slug
 * SHALL be non-empty, lowercase, contain only [a-z0-9-], have no consecutive hyphens,
 * and not start or end with a hyphen. Collision resolution SHALL produce unique slugs.</p>
 *
 * <p><b>Validates: Requirements 1.2, 1.3</b></p>
 */
@Tag("skill-crud")
@Tag("Property-1-Slug-generation-produces-well-formed-unique-identifiers")
class SkillSlugGenerationPropertyTest {

    /**
     * Property: Slug contains only allowed characters [a-z0-9-].
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    void slugContainsOnlyAllowedCharacters(@ForAll("validSkillNames") String name) {
        String slug = SkillSlugGenerator.generateSlug(name);

        assertThat(slug).matches("^[a-z0-9\\-]*$");
    }

    /**
     * Property: Slug is always lowercase.
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    void slugIsAlwaysLowercase(@ForAll("validSkillNames") String name) {
        String slug = SkillSlugGenerator.generateSlug(name);

        assertThat(slug).isEqualTo(slug.toLowerCase());
    }

    /**
     * Property: Slug never contains consecutive hyphens.
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    void slugHasNoConsecutiveHyphens(@ForAll("validSkillNames") String name) {
        String slug = SkillSlugGenerator.generateSlug(name);

        assertThat(slug).doesNotContain("--");
    }

    /**
     * Property: Slug does not start or end with a hyphen.
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    void slugDoesNotStartOrEndWithHyphen(@ForAll("validSkillNames") String name) {
        String slug = SkillSlugGenerator.generateSlug(name);

        if (!slug.isEmpty()) {
            assertThat(slug.charAt(0)).isNotEqualTo('-');
            assertThat(slug.charAt(slug.length() - 1)).isNotEqualTo('-');
        }
    }

    /**
     * Property: Slug is non-empty for any valid skill name containing at least one alphanumeric character.
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    void slugIsNonEmptyForNamesWithAlphanumericChars(@ForAll("validSkillNamesWithAlphanumeric") String name) {
        String slug = SkillSlugGenerator.generateSlug(name);

        assertThat(slug).isNotEmpty();
    }

    /**
     * Property: Collision resolution produces unique slugs — when existing slugs collide,
     * the generator appends numeric suffixes that are all distinct.
     *
     * Validates: Requirements 1.3
     */
    @Property(tries = 100)
    void collisionResolutionProducesUniqueSlugs(@ForAll("validSkillNamesWithAlphanumeric") String name) {
        SkillSlugGenerator generator = new SkillSlugGenerator();
        SkillRepository repository = mock(SkillRepository.class);
        UUID orgId = UUID.randomUUID();

        String baseSlug = SkillSlugGenerator.generateSlug(name);

        // Simulate collisions for base slug and first few suffixed variants
        Set<String> existingSlugs = new HashSet<>();
        existingSlugs.add(baseSlug);
        existingSlugs.add(baseSlug + "-2");
        existingSlugs.add(baseSlug + "-3");

        when(repository.existsByOrganizationIdAndSlug(eq(orgId), any(String.class)))
                .thenAnswer(invocation -> {
                    String slug = invocation.getArgument(1);
                    return existingSlugs.contains(slug);
                });

        String uniqueSlug = generator.generateUniqueSlug(name, orgId, repository);

        // The unique slug must not be in the set of existing slugs
        assertThat(uniqueSlug).isNotIn(existingSlugs);
        // The unique slug must still be well-formed
        assertThat(uniqueSlug).matches("^[a-z0-9\\-]+$");
        assertThat(uniqueSlug).doesNotContain("--");
        assertThat(uniqueSlug.charAt(0)).isNotEqualTo('-');
        assertThat(uniqueSlug.charAt(uniqueSlug.length() - 1)).isNotEqualTo('-');
    }

    /**
     * Property: Collision resolution produces distinct slugs for independent calls
     * with different collision states.
     *
     * Validates: Requirements 1.3
     */
    @Property(tries = 100)
    void collisionResolutionProducesDistinctSlugsPerCollisionState(@ForAll("validSkillNamesWithAlphanumeric") String name) {
        SkillSlugGenerator generator = new SkillSlugGenerator();
        UUID orgId = UUID.randomUUID();
        String baseSlug = SkillSlugGenerator.generateSlug(name);

        // No collision: returns base slug
        SkillRepository repoNoCollision = mock(SkillRepository.class);
        when(repoNoCollision.existsByOrganizationIdAndSlug(eq(orgId), any(String.class)))
                .thenReturn(false);
        String slugNoCollision = generator.generateUniqueSlug(name, orgId, repoNoCollision);

        // One collision: returns base-2
        SkillRepository repoOneCollision = mock(SkillRepository.class);
        when(repoOneCollision.existsByOrganizationIdAndSlug(orgId, baseSlug)).thenReturn(true);
        when(repoOneCollision.existsByOrganizationIdAndSlug(orgId, baseSlug + "-2")).thenReturn(false);
        String slugOneCollision = generator.generateUniqueSlug(name, orgId, repoOneCollision);

        assertThat(slugNoCollision).isEqualTo(baseSlug);
        assertThat(slugOneCollision).isEqualTo(baseSlug + "-2");
        assertThat(slugNoCollision).isNotEqualTo(slugOneCollision);
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> validSkillNames() {
        // Valid skill names: 3–200 chars from [a-zA-Z0-9 \-_]
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_")
                .ofMinLength(3)
                .ofMaxLength(200);
    }

    @Provide
    Arbitrary<String> validSkillNamesWithAlphanumeric() {
        // Valid skill names that contain at least one alphanumeric character
        return validSkillNames()
                .filter(name -> name.chars().anyMatch(Character::isLetterOrDigit));
    }
}
