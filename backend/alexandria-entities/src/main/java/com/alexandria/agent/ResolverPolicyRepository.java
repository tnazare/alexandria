package com.alexandria.agent;

import com.alexandria.agent.entity.ResolverPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the per-organization resolver policy. At most one row per organization.
 */
@Repository
public interface ResolverPolicyRepository extends JpaRepository<ResolverPolicyEntity, UUID> {

    Optional<ResolverPolicyEntity> findByOrganizationId(UUID organizationId);
}
