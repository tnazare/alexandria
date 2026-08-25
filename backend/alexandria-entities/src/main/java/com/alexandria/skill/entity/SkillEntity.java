package com.alexandria.skill.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * JPA entity for the skills table.
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
public class SkillEntity extends BaseEntity {

    @Column(name = "owner_team_id", nullable = false)
    private UUID ownerTeamId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String description;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus = "Draft";

    @Column(nullable = false)
    private String visibility = "private";

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "search_vector", insertable = false, updatable = false)
    private String searchVector;
}
