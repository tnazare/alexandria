package com.alexandria.agent.entity;

import com.alexandria.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the service_tokens table — a scoped, non-interactive credential identifying a
 * headless/CI agent or sandboxed cloud-agent. The presented secret is never stored; only its
 * SHA-256 hash ({@code tokenHash}) and a short non-secret display prefix ({@code tokenPrefix}).
 */
@Entity
@Table(name = "service_tokens")
@Getter
@Setter
public class ServiceTokenEntity extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false)
    private String tokenPrefix;

    @Column(name = "scope", nullable = false)
    private String scope = "agent_read";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
