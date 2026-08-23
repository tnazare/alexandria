package com.alexandria.user;

import com.alexandria.common.dto.PageResponse;
import com.alexandria.user.dto.CurrentUserResponse;
import com.alexandria.user.dto.UpdateRoleRequest;
import com.alexandria.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * REST API contract for user management endpoints.
 */
public interface UserApi {

    /**
     * Returns the authenticated user's profile.
     */
    @GetMapping("/api/me")
    ResponseEntity<CurrentUserResponse> getCurrentUser();

    /**
     * Lists users in the authenticated user's organization (paginated).
     * Requires Org_Admin role.
     */
    @GetMapping("/api/users")
    ResponseEntity<PageResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    );

    /**
     * Changes a user's organization-level role.
     * Requires Org_Admin role.
     */
    @PatchMapping("/api/users/{userId}/role")
    ResponseEntity<UserResponse> changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateRoleRequest request
    );
}
