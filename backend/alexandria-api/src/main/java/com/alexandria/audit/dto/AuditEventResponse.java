package com.alexandria.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for audit event records.
 */
public record AuditEventResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
