package com.alexandria.agent.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity for the resolver_policies table — the organization-level policy governing how the
 * agent resolver handles skills by risk level (reference-mode forcing, commit permission) and the
 * default reference mode. One row per organization; when absent, code defaults apply
 * (permissive low/medium, pin-and-revalidate high/restricted).
 */
@Entity
@Table(name = "resolver_policies")
@Getter
@Setter
public class ResolverPolicyEntity extends BaseEntity {

    @Column(name = "high_risk_policy", nullable = false)
    private String highRiskPolicy = "pin_and_revalidate";

    @Column(name = "restricted_risk_policy", nullable = false)
    private String restrictedRiskPolicy = "pin_and_revalidate";

    @Column(name = "default_reference_mode", nullable = false)
    private String defaultReferenceMode = "floating_latest_approved";
}
