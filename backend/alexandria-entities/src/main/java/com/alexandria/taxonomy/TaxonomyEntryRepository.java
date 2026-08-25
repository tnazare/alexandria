package com.alexandria.taxonomy;

import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for taxonomy entry entities.
 */
@Repository
public interface TaxonomyEntryRepository extends JpaRepository<TaxonomyEntryEntity, UUID> {

    List<TaxonomyEntryEntity> findByOrganizationIdOrderByTagTypeAscValueAsc(UUID organizationId);

    List<TaxonomyEntryEntity> findByOrganizationIdAndTagTypeOrderByValueAsc(UUID organizationId, String tagType);

    boolean existsByOrganizationIdAndTagTypeAndValueIgnoreCase(UUID organizationId, String tagType, String value);

    List<TaxonomyEntryEntity> findByOrganizationIdAndIdIn(UUID organizationId, List<UUID> ids);
}
