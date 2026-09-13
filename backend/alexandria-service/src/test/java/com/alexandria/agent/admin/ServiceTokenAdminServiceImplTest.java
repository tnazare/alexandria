package com.alexandria.agent.admin;

import com.alexandria.agent.ServiceTokenRepository;
import com.alexandria.agent.admin.dto.CreateServiceTokenResponse;
import com.alexandria.agent.entity.ServiceTokenEntity;
import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ServiceTokenHasher;
import com.alexandria.user.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ServiceTokenAdminServiceImpl")
@ExtendWith(MockitoExtension.class)
class ServiceTokenAdminServiceImplTest {

    @Mock
    private ServiceTokenRepository serviceTokenRepository;

    @Mock
    private AuditService auditService;

    private ServiceTokenAdminServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final AlexandriaUserDetails admin =
            new AlexandriaUserDetails(adminId, orgId, Role.Org_Admin, "admin@example.com");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ServiceTokenAdminServiceImpl(serviceTokenRepository, auditService);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists a token in the admin's org with agent_read scope and the hash of the secret")
        void persistsHashedToken() {
            when(serviceTokenRepository.save(any(ServiceTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Instant expiry = Instant.now().plusSeconds(3600);
            CreateServiceTokenResponse response = service.create("ci-bot", expiry, admin);

            ArgumentCaptor<ServiceTokenEntity> captor = ArgumentCaptor.forClass(ServiceTokenEntity.class);
            verify(serviceTokenRepository).save(captor.capture());
            ServiceTokenEntity saved = captor.getValue();

            assertThat(saved.getOrganizationId()).isEqualTo(orgId);
            assertThat(saved.getName()).isEqualTo("ci-bot");
            assertThat(saved.getScope()).isEqualTo("agent_read");
            assertThat(saved.getCreatedBy()).isEqualTo(adminId);
            assertThat(saved.getExpiresAt()).isEqualTo(expiry);
            // The secret is never stored; only its SHA-256 hash.
            assertThat(saved.getTokenHash()).isEqualTo(ServiceTokenHasher.sha256Hex(response.token()));
            assertThat(saved.getTokenHash()).isNotEqualTo(response.token());
        }

        @Test
        @DisplayName("returns the raw secret exactly once, prefixed alx_ with a 12-char display prefix")
        void returnsRawSecret() {
            when(serviceTokenRepository.save(any(ServiceTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            CreateServiceTokenResponse response = service.create("ci-bot", null, admin);

            assertThat(response.token()).startsWith("alx_");
            assertThat(response.tokenPrefix()).hasSize(12);
            assertThat(response.token()).startsWith(response.tokenPrefix());
            assertThat(response.scope()).isEqualTo("agent_read");
        }

        @Test
        @DisplayName("records a service_token.created audit event")
        void recordsAudit() {
            UUID generatedId = UUID.randomUUID();
            when(serviceTokenRepository.save(any(ServiceTokenEntity.class)))
                    .thenAnswer(inv -> {
                        ServiceTokenEntity e = inv.getArgument(0);
                        e.setId(generatedId);
                        return e;
                    });

            service.create("ci-bot", null, admin);

            verify(auditService).record(eq(orgId), eq(adminId), eq("service_token.created"),
                    eq("service_token"), eq(generatedId), anyMap());
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("stamps revokedAt and records a service_token.revoked audit event")
        void revokesActiveToken() {
            UUID tokenId = UUID.randomUUID();
            ServiceTokenEntity entity = new ServiceTokenEntity();
            entity.setId(tokenId);
            entity.setOrganizationId(orgId);
            entity.setTokenPrefix("alx_abcd1234");
            when(serviceTokenRepository.findByIdAndOrganizationId(tokenId, orgId))
                    .thenReturn(Optional.of(entity));

            service.revoke(tokenId, admin);

            assertThat(entity.getRevokedAt()).isNotNull();
            verify(serviceTokenRepository).save(entity);
            verify(auditService).record(eq(orgId), eq(adminId), eq("service_token.revoked"),
                    eq("service_token"), eq(tokenId), anyMap());
        }

        @Test
        @DisplayName("is idempotent: an already-revoked token is not re-stamped or re-audited")
        void idempotentOnAlreadyRevoked() {
            UUID tokenId = UUID.randomUUID();
            Instant originalRevokedAt = Instant.now().minusSeconds(600);
            ServiceTokenEntity entity = new ServiceTokenEntity();
            entity.setId(tokenId);
            entity.setOrganizationId(orgId);
            entity.setRevokedAt(originalRevokedAt);
            when(serviceTokenRepository.findByIdAndOrganizationId(tokenId, orgId))
                    .thenReturn(Optional.of(entity));

            service.revoke(tokenId, admin);

            assertThat(entity.getRevokedAt()).isEqualTo(originalRevokedAt);
            verify(serviceTokenRepository, never()).save(any());
            verify(auditService, never()).record(any(), any(), eq("service_token.revoked"),
                    any(), any(), anyMap());
        }

        @Test
        @DisplayName("throws ServiceTokenNotFoundException when the id is absent or in another org")
        void notFound() {
            UUID tokenId = UUID.randomUUID();
            when(serviceTokenRepository.findByIdAndOrganizationId(tokenId, orgId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revoke(tokenId, admin))
                    .isInstanceOf(ServiceTokenNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("list")
    class ListTokens {

        @Test
        @DisplayName("maps org tokens to metadata responses without exposing the hash")
        void mapsMetadata() {
            ServiceTokenEntity entity = new ServiceTokenEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(orgId);
            entity.setName("ci-bot");
            entity.setTokenHash("deadbeef");
            entity.setTokenPrefix("alx_abcd1234");
            entity.setScope("agent_read");
            entity.setCreatedBy(adminId);
            when(serviceTokenRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId))
                    .thenReturn(List.of(entity));

            var responses = service.list(admin);

            assertThat(responses).hasSize(1);
            assertThat(responses.getFirst().tokenPrefix()).isEqualTo("alx_abcd1234");
            assertThat(responses.getFirst().name()).isEqualTo("ci-bot");
            assertThat(responses.getFirst().scope()).isEqualTo("agent_read");
        }
    }
}
