package com.alexandria.usage.entity;

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
 * An immutable, append-only record that a user copied or exported a skill.
 *
 * <p>Written on every successful copy/export (Skill Export milestone) and, in a later
 * milestone, on views. Records are never modified after creation, so this entity does not
 * extend {@code BaseEntity} (no {@code updated_at}); {@code created_at} is set on insert.</p>
 */
@Entity
@Table(name = "usage_events")
@Getter
@Setter
public class UsageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "skill_version_id", updatable = false)
    private UUID skillVersionId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /** One of {@code view}, {@code copy}, {@code export}. This milestone writes only copy/export. */
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** How the export was consumed as sent by the client: {@code copy} or {@code download}. */
    @Column(updatable = false)
    private String source;

    /** The {@code ExportFormat} wire value the artifact was rendered into. */
    @Column(name = "export_format", updatable = false)
    private String exportFormat;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
