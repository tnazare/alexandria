package com.alexandria.audit.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter criteria for querying audit events. All filters combine with AND logic.
 *
 * @param targetType  filter by target entity type (e.g. "user", "team")
 * @param targetId    filter by specific target entity ID
 * @param actorUserId filter by the user who performed the action
 * @param action      filter by action type (e.g. "user.role_changed")
 * @param startDate   include events created at or after this timestamp
 * @param endDate     include events created at or before this timestamp
 */
public record AuditEventFilter(
        String targetType,
        UUID targetId,
        UUID actorUserId,
        String action,
        Instant startDate,
        Instant endDate
) {
}
