package com.alexandria.audit;

import com.alexandria.audit.dto.AuditEventResponse;
import com.alexandria.common.dto.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.UUID;

/**
 * REST API contract for audit event query endpoints.
 *
 * <p>Access restricted to Org_Admin and Read_Only_Auditor roles.</p>
 */
public interface AuditApi {

    /**
     * Lists audit events for the authenticated user's organization with optional filters.
     * All provided filters combine with AND logic. Results are ordered by created_at descending.
     */
    @GetMapping("/api/audit-events")
    ResponseEntity<PageResponse<AuditEventResponse>> listAuditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "target_id", required = false) UUID targetId,
            @RequestParam(name = "actor_user_id", required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "start_date", required = false) Instant startDate,
            @RequestParam(name = "end_date", required = false) Instant endDate
    );
}
