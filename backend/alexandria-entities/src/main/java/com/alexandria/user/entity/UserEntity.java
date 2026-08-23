package com.alexandria.user.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity for the users table. Stores authenticated users linked to an organization.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class UserEntity extends BaseEntity {

    @Column(name = "external_subject", nullable = false)
    private String externalSubject;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String role = "Skill_Consumer";

    @Column(nullable = false)
    private boolean enabled = true;
}
