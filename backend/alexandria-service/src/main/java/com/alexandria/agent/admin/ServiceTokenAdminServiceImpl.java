package com.alexandria.agent.admin;

import com.alexandria.agent.ServiceTokenRepository;
import com.alexandria.agent.admin.dto.CreateServiceTokenResponse;
import com.alexandria.agent.admin.dto.ServiceTokenResponse;
import com.alexandria.agent.entity.ServiceTokenEntity;
import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ServiceTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link ServiceTokenAdminService}. Generates a secret via the shared
 * {@link ServiceTokenHasher} so a minted token hashes to exactly what {@code ServiceTokenAuthFilter}
 * looks up, persisting only the hash and a non-secret display prefix. Every mint and revoke is
 * audited.
 */
@Service
@RequiredArgsConstructor
public class ServiceTokenAdminServiceImpl implements ServiceTokenAdminService {

    /** The only scope currently granted to service tokens. */
    private static final String SCOPE_AGENT_READ = "agent_read";

    private final ServiceTokenRepository serviceTokenRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public CreateServiceTokenResponse create(String name, Instant expiresAt, AlexandriaUserDetails admin) {
        String secret = ServiceTokenHasher.generateSecret();
        String tokenHash = ServiceTokenHasher.sha256Hex(secret);
        String tokenPrefix = ServiceTokenHasher.displayPrefix(secret);

        ServiceTokenEntity entity = new ServiceTokenEntity();
        entity.setOrganizationId(admin.organizationId());
        entity.setName(name);
        entity.setTokenHash(tokenHash);
        entity.setTokenPrefix(tokenPrefix);
        entity.setScope(SCOPE_AGENT_READ);
        entity.setCreatedBy(admin.userId());
        entity.setExpiresAt(expiresAt);

        ServiceTokenEntity saved = serviceTokenRepository.save(entity);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", name);
        metadata.put("tokenPrefix", tokenPrefix);
        metadata.put("scope", SCOPE_AGENT_READ);
        if (expiresAt != null) {
            metadata.put("expiresAt", expiresAt.toString());
        }
        auditService.record(admin.organizationId(), admin.actorUserId(),
                "service_token.created", "service_token", saved.getId(), metadata);

        return new CreateServiceTokenResponse(
                saved.getId(),
                saved.getName(),
                saved.getTokenPrefix(),
                saved.getScope(),
                saved.getExpiresAt(),
                secret);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceTokenResponse> list(AlexandriaUserDetails admin) {
        return serviceTokenRepository
                .findByOrganizationIdOrderByCreatedAtDesc(admin.organizationId())
                .stream()
                .map(ServiceTokenAdminServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(UUID tokenId, AlexandriaUserDetails admin) {
        ServiceTokenEntity entity = serviceTokenRepository
                .findByIdAndOrganizationId(tokenId, admin.organizationId())
                .orElseThrow(() -> new ServiceTokenNotFoundException(
                        "Service token not found: " + tokenId));

        // Idempotent: only stamp revokedAt (and audit) the first time.
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(Instant.now());
            serviceTokenRepository.save(entity);
            auditService.record(admin.organizationId(), admin.actorUserId(),
                    "service_token.revoked", "service_token", entity.getId(),
                    Map.of("tokenPrefix", entity.getTokenPrefix()));
        }
    }

    private static ServiceTokenResponse toResponse(ServiceTokenEntity entity) {
        return new ServiceTokenResponse(
                entity.getId(),
                entity.getName(),
                entity.getTokenPrefix(),
                entity.getScope(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getLastUsedAt());
    }
}
