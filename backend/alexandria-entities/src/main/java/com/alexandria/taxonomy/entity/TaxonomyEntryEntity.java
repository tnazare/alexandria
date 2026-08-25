package com.alexandria.taxonomy.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity for the taxonomy_entries table.
 */
@Entity
@Table(name = "taxonomy_entries")
@Getter
@Setter
public class TaxonomyEntryEntity extends BaseEntity {

    @Column(name = "tag_type", nullable = false)
    private String tagType;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "description")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
