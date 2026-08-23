package com.alexandria.user;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.common.dto.PageResponse;
import com.alexandria.user.dto.CurrentUserResponse;
import com.alexandria.user.dto.UpdateRoleRequest;
import com.alexandria.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for user management endpoints.
 */
@RestController
@RequiredArgsConstructor
public class UserResource implements UserApi {

    private final UserService userService;

    @Override
    public ResponseEntity<CurrentUserResponse> getCurrentUser() {
        var userDetails = getAuthenticatedUser();
        return ResponseEntity.ok(userService.getCurrentUser(userDetails));
    }

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<PageResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userDetails = getAuthenticatedUser();
        var pageable = PageRequest.of(page, Math.min(size, 100));
        var result = userService.listUsers(userDetails.organizationId(), pageable);

        var response = new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<UserResponse> changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateRoleRequest request) {
        var userDetails = getAuthenticatedUser();
        var result = userService.changeRole(
                userDetails.organizationId(),
                userDetails.userId(),
                userId,
                request.role()
        );
        return ResponseEntity.ok(result);
    }

    private AlexandriaUserDetails getAuthenticatedUser() {
        return (AlexandriaUserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();
    }
}
