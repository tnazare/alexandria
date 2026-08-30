package com.alexandria.usage;

import com.alexandria.usage.entity.UsageEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for append-only {@link UsageEventEntity} records.
 *
 * <p>Only {@code save} is used on the write path (fire-and-forget). The count/list methods
 * support tests and later analytics; no update or delete paths are exposed.</p>
 */
public interface UsageEventRepository extends JpaRepository<UsageEventEntity, UUID> {

    long countBySkillIdAndEventType(UUID skillId, String eventType);

    List<UsageEventEntity> findByOrganizationId(UUID organizationId);
}
