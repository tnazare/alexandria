package com.alexandria.user;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.user.dto.CurrentUserResponse;
import com.alexandria.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for user management operations.
 */
public interface UserService {

    /**
     * Returns the profile of the currently authenticated user.
     */
    CurrentUserResponse getCurrentUser(AlexandriaUserDetails userDetails);

    /**
     * Lists users in the given organization with pagination.
     */
    Page<UserResponse> listUsers(UUID orgId, Pageable pageable);

    /**
     * Changes the role of a user in the given organization.
     *
     * @return the updated user response
     * @throws jakarta.persistence.EntityNotFoundException if user not found in the organization
     */
    UserResponse changeRole(UUID orgId, UUID actorUserId, UUID userId, String newRole);
}
