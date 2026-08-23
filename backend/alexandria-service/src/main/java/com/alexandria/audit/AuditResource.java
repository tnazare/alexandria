package com.alexandria.audit;

import com.alexandria.audit.dto.AuditEventFilter;
import com.alexandria.audit.dto.AuditEventResponse;
import com.alexandria.audit.entity.AuditEventEntity;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.common.dto.PageResponse;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.UserEntity;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for querying audit events.
 *
 * <p>Provides paginated, filterable access to audit events scoped to the authenticated
 * user's organization. Access is restricted to Org_Admin and Read_Only_Auditor roles.</p>
 */
@RestController
@RequiredArgsConstructor
public class AuditResource implements AuditApi {

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;

    @Override
    @PreAuthorize("hasAnyRole('Org_Admin', 'Read_Only_Auditor')")
    public ResponseEntity<PageResponse<AuditEventResponse>> listAuditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "target_id", required = false) UUID targetId,
            @RequestParam(name = "actor_user_id", required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "start_date", required = false) Instant startDate,
            @RequestParam(name = "end_date", required = false) Instant endDate
    ) {
        var userDetails = getAuthenticatedUser();
        UUID orgId = userDetails.organizationId();

        // Clamp page size to max 100
        int clampedSize = Math.min(Math.max(size, 1), 100);

        var filter = new AuditEventFilter(targetType, targetId, actorUserId, action, startDate, endDate);
        var pageable = PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditEventEntity> spec = buildSpecification(orgId, filter);
        Page<AuditEventEntity> resultPage = auditEventRepository.findAll(spec, pageable);

        // Resolve actor emails in batch
        var actorUserIds = resultPage.getContent().stream()
                .map(AuditEventEntity::getActorUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<UUID, String> emailsByUserId = userRepository.findAllById(actorUserIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getEmail));

        var responseContent = resultPage.getContent().stream()
                .map(entity -> toResponse(entity, emailsByUserId))
                .toList();

        var pageResponse = new PageResponse<>(
                responseContent,
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );

        return ResponseEntity.ok(pageResponse);
    }

    private Specification<AuditEventEntity> buildSpecification(UUID orgId, AuditEventFilter filter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            // Always scope to organization
            predicates.add(cb.equal(root.get("organizationId"), orgId));

            if (filter.targetType() != null) {
                predicates.add(cb.equal(root.get("targetType"), filter.targetType()));
            }
            if (filter.targetId() != null) {
                predicates.add(cb.equal(root.get("targetId"), filter.targetId()));
            }
            if (filter.actorUserId() != null) {
                predicates.add(cb.equal(root.get("actorUserId"), filter.actorUserId()));
            }
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.startDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.startDate()));
            }
            if (filter.endDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.endDate()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditEventResponse toResponse(AuditEventEntity entity, Map<UUID, String> emailsByUserId) {
        String actorEmail = entity.getActorUserId() != null
                ? emailsByUserId.getOrDefault(entity.getActorUserId(), null)
                : null;

        return new AuditEventResponse(
                entity.getId(),
                entity.getActorUserId(),
                actorEmail,
                entity.getAction(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getMetadata(),
                entity.getCreatedAt()
        );
    }

    private AlexandriaUserDetails getAuthenticatedUser() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return (AlexandriaUserDetails) authentication.getPrincipal();
    }
}
