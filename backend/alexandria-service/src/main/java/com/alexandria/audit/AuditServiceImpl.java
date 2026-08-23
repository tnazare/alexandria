package com.alexandria.audit;

import com.alexandria.audit.entity.AuditEventEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Fire-and-forget implementation of {@link AuditService}.
 *
 * <p>All exceptions during audit event persistence are caught, logged at ERROR level
 * with full event details, and swallowed — ensuring that audit infrastructure failures
 * never propagate to callers or disrupt business operations.</p>
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final AuditEventRepository auditEventRepository;

    @Override
    public void record(UUID orgId, UUID actorUserId, String action, String targetType, UUID targetId, Map<String, Object> metadata) {
        try {
            var entity = new AuditEventEntity();
            entity.setOrganizationId(orgId);
            entity.setActorUserId(actorUserId);
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setMetadata(metadata != null ? metadata : Map.of());

            auditEventRepository.save(entity);
        } catch (Exception ex) {
            log.error("Failed to record audit event: orgId={}, actorUserId={}, action={}, targetType={}, targetId={}, metadata={}",
                    orgId, actorUserId, action, targetType, targetId, metadata, ex);
        }
    }
}
