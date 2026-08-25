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
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for taxonomy deactivation behavior.
 *
 * <p>Property 14: Taxonomy deactivation preserves existing references —
 * when a taxonomy entry is deactivated, its active field is set to false,
 * existing skill-tag associations referencing the entry remain intact (not deleted),
 * and deactivation does not throw or fail for entries in use.</p>
 *
 * <p>Validates: Requirements 7.5, 7.12</p>
 */
@Tag("Feature: skill-crud, Property 14: Taxonomy deactivation preserves existing references")
class TaxonomyDeactivationPropertyTest {

    /**
     * Property: Deactivation sets the entry's active field to false.
     *
     * <p>For any active taxonomy entry, calling deactivate results in save()
     * being called with active=false on that entry.</p>
     *
     * Validates: Requirements 7.5, 7.12
     */
    @Property(tries = 100)
    void deactivationSetsActiveFieldToFalse(@ForAll("activeTaxonomyScenarios") DeactivationScenario scenario) {
        // Arrange
        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);
        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        TaxonomyEntryEntity entity = scenario.toEntity();
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.save(any(TaxonomyEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TaxonomyEntryEntity result = service.deactivate(scenario.orgId(), entity.getId(), scenario.actorUserId());

        // Assert
        assertThat(result.isActive()).isFalse();

        ArgumentCaptor<TaxonomyEntryEntity> captor = ArgumentCaptor.forClass(TaxonomyEntryEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    /**
     * Property: Deactivation never calls delete on the repository.
     *
     * <p>Existing skill-tag associations referencing the entry remain intact.
     * The deactivate operation only updates the entry — it never removes it.</p>
     *
     * Validates: Requirements 7.5, 7.12
     */
    @Property(tries = 100)
    void deactivationDoesNotDeleteEntry(@ForAll("activeTaxonomyScenarios") DeactivationScenario scenario) {
        // Arrange
        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);
        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        TaxonomyEntryEntity entity = scenario.toEntity();
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.save(any(TaxonomyEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.deactivate(scenario.orgId(), entity.getId(), scenario.actorUserId());

        // Assert — verify no delete methods are called
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
        verify(repository, never()).deleteAll();
        verify(repository, never()).deleteAllById(any());
    }

    /**
     * Property: Deactivation does not throw for entries that are in use.
     *
     * <p>Even when a taxonomy entry is referenced by existing skill-tag associations,
     * deactivation completes successfully without throwing any exception.</p>
     *
     * Validates: Requirements 7.5, 7.12
     */
    @Property(tries = 100)
    void deactivationDoesNotThrowForEntriesInUse(@ForAll("activeTaxonomyScenarios") DeactivationScenario scenario) {
        // Arrange
        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);
        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        TaxonomyEntryEntity entity = scenario.toEntity();
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.save(any(TaxonomyEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act & Assert — should not throw regardless of whether entry is in use
        assertThatCode(() -> service.deactivate(scenario.orgId(), entity.getId(), scenario.actorUserId()))
                .doesNotThrowAnyException();
    }

    /**
     * Property: Deactivation preserves the entry's original data (tagType, value, description).
     *
     * <p>Only the active flag changes; all other fields remain untouched.</p>
     *
     * Validates: Requirements 7.5, 7.12
     */
    @Property(tries = 100)
    void deactivationPreservesEntryData(@ForAll("activeTaxonomyScenarios") DeactivationScenario scenario) {
        // Arrange
        TaxonomyEntryRepository repository = mock(TaxonomyEntryRepository.class);
        AuditService auditService = mock(AuditService.class);
        TaxonomyServiceImpl service = new TaxonomyServiceImpl(repository, auditService);

        TaxonomyEntryEntity entity = scenario.toEntity();
        String originalTagType = entity.getTagType();
        String originalValue = entity.getValue();
        String originalDescription = entity.getDescription();

        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.save(any(TaxonomyEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TaxonomyEntryEntity result = service.deactivate(scenario.orgId(), entity.getId(), scenario.actorUserId());

        // Assert — only active changed, everything else preserved
        assertThat(result.getTagType()).isEqualTo(originalTagType);
        assertThat(result.getValue()).isEqualTo(originalValue);
        assertThat(result.getDescription()).isEqualTo(originalDescription);
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<DeactivationScenario> activeTaxonomyScenarios() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> tagTypes = Arbitraries.of("task_type", "tool", "language", "framework");
        Arbitrary<String> values = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(100);
        Arbitrary<String> descriptions = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(500)
                .injectNull(0.3);

        return Combinators.combine(uuids, uuids, uuids, tagTypes, values, descriptions)
                .as(DeactivationScenario::new);
    }

    /**
     * Test scenario encapsulating all inputs for a taxonomy deactivation operation.
     */
    record DeactivationScenario(
            UUID orgId,
            UUID entryId,
            UUID actorUserId,
            String tagType,
            String value,
            String description
    ) {
        TaxonomyEntryEntity toEntity() {
            TaxonomyEntryEntity entity = new TaxonomyEntryEntity();
            entity.setId(entryId);
            entity.setOrganizationId(orgId);
            entity.setTagType(tagType);
            entity.setValue(value);
            entity.setDescription(description);
            entity.setActive(true);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        }
    }
}
