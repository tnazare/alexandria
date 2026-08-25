package com.alexandria.taxonomy;

import com.alexandria.audit.AuditService;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of taxonomy CRUD operations with uniqueness validation
 * and audit event recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxonomyServiceImpl implements TaxonomyService {

    private static final Set<String> VALID_TAG_TYPES = Set.of("task_type", "tool", "language", "framework");

    private final TaxonomyEntryRepository taxonomyEntryRepository;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<TaxonomyEntryEntity>> getAllGrouped(UUID orgId) {
        List<TaxonomyEntryEntity> entries = taxonomyEntryRepository
                .findByOrganizationIdOrderByTagTypeAscValueAsc(orgId);

        return entries.stream()
                .collect(Collectors.groupingBy(TaxonomyEntryEntity::getTagType));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxonomyEntryEntity> getByType(UUID orgId, String tagType) {
        validateTagType(tagType);
        return taxonomyEntryRepository
                .findByOrganizationIdAndTagTypeOrderByValueAsc(orgId, tagType);
    }

    @Override
    @Transactional
    public TaxonomyEntryEntity create(UUID orgId, String tagType, String value, String description, UUID actorUserId) {
        validateTagType(tagType);

        if (taxonomyEntryRepository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(orgId, tagType, value)) {
            throw new TaxonomyConflictException(
                    "Taxonomy entry with value '" + value + "' already exists for type '" + tagType + "'");
        }

        TaxonomyEntryEntity entry = new TaxonomyEntryEntity();
        entry.setOrganizationId(orgId);
        entry.setTagType(tagType);
        entry.setValue(value);
        entry.setDescription(description);
        entry.setActive(true);

        entry = taxonomyEntryRepository.save(entry);

        auditService.record(orgId, actorUserId, "taxonomy.created", "taxonomy_entry", entry.getId(),
                Map.of("tagType", tagType, "value", value));

        return entry;
    }

    @Override
    @Transactional
    public TaxonomyEntryEntity update(UUID orgId, UUID tagId, String value, String description, UUID actorUserId) {
        TaxonomyEntryEntity entry = taxonomyEntryRepository.findById(tagId)
                .filter(e -> e.getOrganizationId().equals(orgId))
                .orElseThrow(() -> new EntityNotFoundException("Taxonomy entry not found"));

        // Check uniqueness only if the value is changing
        if (!entry.getValue().equalsIgnoreCase(value)) {
            if (taxonomyEntryRepository.existsByOrganizationIdAndTagTypeAndValueIgnoreCase(orgId, entry.getTagType(), value)) {
                throw new TaxonomyConflictException(
                        "Taxonomy entry with value '" + value + "' already exists for type '" + entry.getTagType() + "'");
            }
        }

        entry.setValue(value);
        entry.setDescription(description);

        entry = taxonomyEntryRepository.save(entry);

        auditService.record(orgId, actorUserId, "taxonomy.updated", "taxonomy_entry", entry.getId(),
                Map.of("tagType", entry.getTagType(), "value", value));

        return entry;
    }

    @Override
    @Transactional
    public TaxonomyEntryEntity deactivate(UUID orgId, UUID tagId, UUID actorUserId) {
        TaxonomyEntryEntity entry = taxonomyEntryRepository.findById(tagId)
                .filter(e -> e.getOrganizationId().equals(orgId))
                .orElseThrow(() -> new EntityNotFoundException("Taxonomy entry not found"));

        entry.setActive(false);
        entry = taxonomyEntryRepository.save(entry);

        auditService.record(orgId, actorUserId, "taxonomy.deactivated", "taxonomy_entry", entry.getId(),
                Map.of("tagType", entry.getTagType(), "value", entry.getValue()));

        return entry;
    }

    /**
     * Validates that the given tag type is one of the supported types.
     *
     * @throws IllegalArgumentException if tagType is not valid
     */
    private void validateTagType(String tagType) {
        if (tagType == null || !VALID_TAG_TYPES.contains(tagType)) {
            throw new IllegalArgumentException(
                    "Invalid tag type: '" + tagType + "'. Must be one of: " + VALID_TAG_TYPES);
        }
    }
}
