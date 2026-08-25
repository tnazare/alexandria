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
 * JPA entity for the skill_access_grants table.
 * Does not extend BaseEntity — access grants have no organization_id or updated_at.
 */
@Entity
@Table(name = "skill_access_grants")
@Getter
@Setter
public class SkillAccessGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @PrePersist
    protected void onCreate() {
        this.grantedAt = Instant.now();
    }
}
