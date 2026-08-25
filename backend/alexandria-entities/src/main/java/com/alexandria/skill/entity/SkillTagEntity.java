package com.alexandria.skill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the skill_tags join table.
 * Does not extend BaseEntity — skill tags have no organization_id or updated_at.
 */
@Entity
@Table(name = "skill_tags")
@Getter
@Setter
public class SkillTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "taxonomy_entry_id", nullable = false)
    private UUID taxonomyEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
