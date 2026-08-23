package com.alexandria.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Service for recording governance audit events.
 *
 * <p>All audit recording uses a fire-and-forget pattern — failures are logged
 * but never propagate to callers. This ensures that audit infrastructure issues
 * do not disrupt business operations.</p>
 */
public interface AuditService {

    /**
     * Records an audit event for a governance action.
     *
     * <p>This method never throws. Any failure during event persistence is caught
     * and logged at ERROR level with full event details.</p>
     *
     * @param orgId       the organization in which the action occurred
     * @param actorUserId the user who performed the action (may be null for system actions)
     * @param action      the action type (e.g. "user.provisioned", "user.role_changed")
     * @param targetType  the type of entity affected (e.g. "user", "team", "team_member")
     * @param targetId    the ID of the affected entity
     * @param metadata    additional context about the action (never null, may be empty)
     */
    void record(UUID orgId, UUID actorUserId, String action, String targetType, UUID targetId, Map<String, Object> metadata);
}
