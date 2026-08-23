package com.alexandria.team;

import com.alexandria.team.entity.TeamEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for team entities. Always query scoped by organizationId.
 */
@Repository
public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    Page<TeamEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Optional<TeamEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);
}
