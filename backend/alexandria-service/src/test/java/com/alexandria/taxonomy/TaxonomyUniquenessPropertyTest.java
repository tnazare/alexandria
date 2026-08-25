package com.alexandria.taxonomy;

import com.alexandria.audit.AuditService;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for taxonomy value uniqueness within type.
 *
 * <p><b>Property 13: Taxonomy value uniqueness within type</b> —
 * for any tag type and value combination, creating a duplicate entry with the same
 * value (case-insensitive) within the same type SHALL be rejected with
 * TaxonomyConflictException. The same value in a different type SHALL be allowed.</p>
 *
 * <p><b>Validates: Requirements 7.10</b></p>
 */
@Tag("skill-crud")
@Tag("Property-13-Taxonomy-value-uniqueness-within-type")
class TaxonomyUniquenessPropertyTest {

    private static final String[] VALID_TAG_TYPES = {"task_type", "tool", "language", "framework"};

    /**
     * Property: Duplicate values (case-insensitive) within the same type are rejected
     * with TaxonomyConflictException.
     *
     * <p>Generates random tag type + value combinations, creates an entry, then attempts
     * to create another entry with a case variation of the same value in the same type.
     * The second creation must throw TaxonomyConflictException.</p>
     *
     * Validates: Requirements 7.10
     */
    @Property(tries = 100)
    void duplicateValueWithinSameTypeIsRejected(
            @ForAll("tagTypeAndValue") TagTypeAndValue input) {

        UUID orgId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();

        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        // First creation succeeds — repository says no existing entry
        when(repository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(
                eq(orgId), eq(input.tagType()), eq(input.value())))
                .thenReturn(false);

        TaxonomyEntryEntity savedEntity = new TaxonomyEntryEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setOrganizationId(orgId);
        savedEntity.setTagType(input.tagType());
        savedEntity.setValue(input.value());
        savedEntity.setActive(true);
        when(repository.save(any(TaxonomyEntryEntity.class))).thenReturn(savedEntity);

        // First create should succeed
        TaxonomyEntryEntity result = service.create(orgId, input.tagType(), input.value(), "desc", actorUserId);
        assertThat(result).isNotNull();
        assertThat(result.getValue()).isEqualTo(input.value());

        // Now simulate that the entry exists — repository returns true for case-insensitive check
        when(repository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(
                eq(orgId), eq(input.tagType()), eq(input.caseVariant())))
                .thenReturn(true);

        // Second create with a case variation must throw TaxonomyConflictException
        assertThatThrownBy(() ->
                service.create(orgId, input.tagType(), input.caseVariant(), "desc2", actorUserId))
                .isInstanceOf(TaxonomyConflictException.class);
    }

    /**
     * Property: The same value in a different tag type is allowed (no conflict).
     *
     * <p>Generates a value and two different tag types. Creating entries with the same
     * value in different types should both succeed without throwing.</p>
     *
     * Validates: Requirements 7.10
     */
    @Property(tries = 100)
    void sameValueInDifferentTypeIsAllowed(
            @ForAll("valueWithTwoDifferentTypes") ValueWithTwoTypes input) {

        UUID orgId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();

        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);

        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        // For type1: no existing entry
        when(repository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(
                eq(orgId), eq(input.type1()), eq(input.value())))
                .thenReturn(false);

        // For type2: no existing entry (same value, different type)
        when(repository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(
                eq(orgId), eq(input.type2()), eq(input.value())))
                .thenReturn(false);

        TaxonomyEntryEntity savedEntity1 = new TaxonomyEntryEntity();
        savedEntity1.setId(UUID.randomUUID());
        savedEntity1.setOrganizationId(orgId);
        savedEntity1.setTagType(input.type1());
        savedEntity1.setValue(input.value());
        savedEntity1.setActive(true);

        TaxonomyEntryEntity savedEntity2 = new TaxonomyEntryEntity();
        savedEntity2.setId(UUID.randomUUID());
        savedEntity2.setOrganizationId(orgId);
        savedEntity2.setTagType(input.type2());
        savedEntity2.setValue(input.value());
        savedEntity2.setActive(true);

        when(repository.save(any(TaxonomyEntryEntity.class)))
                .thenReturn(savedEntity1)
                .thenReturn(savedEntity2);

        // Both creates should succeed — same value, different types
        TaxonomyEntryEntity result1 = service.create(orgId, input.type1(), input.value(), "desc", actorUserId);
        assertThat(result1).isNotNull();

        TaxonomyEntryEntity result2 = service.create(orgId, input.type2(), input.value(), "desc", actorUserId);
        assertThat(result2).isNotNull();
    }

    // --- Providers ---

    @Provide
    Arbitrary<TagTypeAndValue> tagTypeAndValue() {
        Arbitrary<String> tagTypes = Arbitraries.of(VALID_TAG_TYPES);
        Arbitrary<String> values = taxonomyValues();

        return Combinators.combine(tagTypes, values).as((tagType, value) -> {
            String caseVariant = generateCaseVariant(value);
            return new TagTypeAndValue(tagType, value, caseVariant);
        });
    }

    @Provide
    Arbitrary<ValueWithTwoTypes> valueWithTwoDifferentTypes() {
        Arbitrary<String> values = taxonomyValues();

        return values.flatMap(value ->
                Arbitraries.of(VALID_TAG_TYPES).flatMap(type1 -> {
                    // Pick a different type from the remaining types
                    String[] otherTypes = java.util.Arrays.stream(VALID_TAG_TYPES)
                            .filter(t -> !t.equals(type1))
                            .toArray(String[]::new);
                    return Arbitraries.of(otherTypes).map(type2 ->
                            new ValueWithTwoTypes(value, type1, type2));
                })
        );
    }

    private Arbitrary<String> taxonomyValues() {
        // Taxonomy values: 1-100 chars, alphanumeric with spaces and hyphens
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_")
                .ofMinLength(1)
                .ofMaxLength(100)
                .filter(v -> !v.isBlank());
    }

    /**
     * Generates a case variant of the given value by toggling the case of each character.
     */
    private static String generateCaseVariant(String original) {
        StringBuilder variant = new StringBuilder(original.length());
        for (char c : original.toCharArray()) {
            if (Character.isLowerCase(c)) {
                variant.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                variant.append(Character.toLowerCase(c));
            } else {
                variant.append(c);
            }
        }
        // If the variant is identical (no alphabetic chars), just uppercase the whole thing
        String result = variant.toString();
        if (result.equals(original)) {
            return original.toUpperCase();
        }
        return result;
    }

    // --- Records ---

    record TagTypeAndValue(String tagType, String value, String caseVariant) {}

    record ValueWithTwoTypes(String value, String type1, String type2) {}
}
