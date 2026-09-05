package com.alexandria.agent;

import com.alexandria.agent.entity.ServiceTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for service tokens. Lookup is by the SHA-256 hash of the presented secret.
 */
@Repository
public interface ServiceTokenRepository extends JpaRepository<ServiceTokenEntity, UUID> {

    Optional<ServiceTokenEntity> findByTokenHash(String tokenHash);
}
