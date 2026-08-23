package com.alexandria.user;

import com.alexandria.user.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for user entities. Always query by organizationId for tenant scoping.
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByOrganizationIdAndExternalSubject(UUID organizationId, String externalSubject);

    Page<UserEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Optional<UserEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
