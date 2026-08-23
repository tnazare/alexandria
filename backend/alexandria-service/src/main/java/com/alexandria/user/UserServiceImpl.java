package com.alexandria.user;

import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.user.dto.CurrentUserResponse;
import com.alexandria.user.dto.UserResponse;
import com.alexandria.user.entity.UserEntity;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Implementation of user management operations.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(AlexandriaUserDetails userDetails) {
        var user = userRepository.findByIdAndOrganizationId(userDetails.userId(), userDetails.organizationId())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getOrganizationId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(UUID orgId, Pageable pageable) {
        return userRepository.findByOrganizationId(orgId, pageable)
                .map(this::toUserResponse);
    }

    @Override
    @Transactional
    public UserResponse changeRole(UUID orgId, UUID actorUserId, UUID userId, String newRole) {
        var user = userRepository.findByIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        String previousRole = user.getRole();
        user.setRole(newRole);
        var saved = userRepository.save(user);

        auditService.record(
                orgId,
                actorUserId,
                "user.role_changed",
                "user",
                userId,
                Map.of(
                        "previous_role", previousRole,
                        "new_role", newRole
                )
        );

        return toUserResponse(saved);
    }

    private UserResponse toUserResponse(UserEntity entity) {
        return new UserResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.isEnabled(),
                entity.getOrganizationId(),
                entity.getCreatedAt()
        );
    }
}
