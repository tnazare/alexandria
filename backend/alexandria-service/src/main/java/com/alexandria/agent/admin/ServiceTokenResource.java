package com.alexandria.agent.admin;

import com.alexandria.agent.admin.dto.CreateServiceTokenRequest;
import com.alexandria.agent.admin.dto.CreateServiceTokenResponse;
import com.alexandria.agent.admin.dto.ServiceTokenResponse;
import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the service-token admin API. Every operation requires the {@code Org_Admin}
 * role and is scoped to the caller's organization by {@link ServiceTokenAdminService}.
 */
@RestController
@RequiredArgsConstructor
public class ServiceTokenResource implements ServiceTokenAdminApi {

    private final ServiceTokenAdminService serviceTokenAdminService;

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<CreateServiceTokenResponse> create(CreateServiceTokenRequest request) {
        CreateServiceTokenResponse response =
                serviceTokenAdminService.create(request.name(), request.expiresAt(), currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<List<ServiceTokenResponse>> list() {
        return ResponseEntity.ok(serviceTokenAdminService.list(currentUser()));
    }

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<Void> revoke(UUID tokenId) {
        serviceTokenAdminService.revoke(tokenId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
