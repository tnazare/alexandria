package com.alexandria.audit;

import com.alexandria.audit.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for audit events. Always query by organizationId.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    Page<AuditEventEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<AuditEventEntity> findByOrganizationIdAndTargetTypeAndTargetId(
            UUID organizationId, String targetType, UUID targetId, Pageable pageable);
}
