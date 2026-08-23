package com.alexandria.team.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * JPA entity for the teams table.
 */
@Entity
@Table(name = "teams")
@Getter
@Setter
public class TeamEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
