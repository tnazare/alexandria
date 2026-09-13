package com.alexandria.agent;

import com.alexandria.agent.entity.ServiceTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for service tokens. Authentication looks a presented token up by the SHA-256 hash of
 * its secret; the admin API lists and revokes tokens scoped to an organization.
 */
@Repository
public interface ServiceTokenRepository extends JpaRepository<ServiceTokenEntity, UUID> {

    Optional<ServiceTokenEntity> findByTokenHash(String tokenHash);

    /** Lists an organization's tokens, most recently created first (admin listing). */
    List<ServiceTokenEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Loads a token by id scoped to its organization (admin revoke; cross-org yields empty). */
    Optional<ServiceTokenEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
