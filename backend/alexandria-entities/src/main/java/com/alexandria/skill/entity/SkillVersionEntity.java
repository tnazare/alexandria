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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity for the skill_versions table.
 * Does not extend BaseEntity — skill versions have no organization_id or updated_at.
 */
@Entity
@Table(name = "skill_versions")
@Getter
@Setter
public class SkillVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "canonical_instructions", nullable = false)
    private String canonicalInstructions;

    @Column
    private String applicability;

    @Column
    private String limitations;

    @Column(name = "when_to_use")
    private String whenToUse;

    @Column
    private String examples;

    @Column
    private String changelog;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_tools", columnDefinition = "text[]")
    private List<String> supportedTools = new ArrayList<>();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
