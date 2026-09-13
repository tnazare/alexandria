package com.alexandria.agent.admin;

import com.alexandria.agent.admin.dto.CreateServiceTokenResponse;
import com.alexandria.agent.admin.dto.ServiceTokenResponse;
import com.alexandria.auth.AlexandriaUserDetails;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service for minting, listing, and revoking service tokens. All operations are scoped
 * to the calling administrator's organization.
 */
public interface ServiceTokenAdminService {

    /**
     * Mints a new service token in the administrator's organization. The returned response is the
     * only place the plaintext secret is ever exposed.
     *
     * @param name      human-readable label
     * @param expiresAt optional expiry (null = never expires)
     * @param admin     the authenticated administrator (supplies organization and creator)
     */
    CreateServiceTokenResponse create(String name, Instant expiresAt, AlexandriaUserDetails admin);

    /** Lists the administrator's organization's tokens (metadata only), most recent first. */
    List<ServiceTokenResponse> list(AlexandriaUserDetails admin);

    /**
     * Revokes a token by id within the administrator's organization. Idempotent: revoking an
     * already-revoked token succeeds. Throws {@link ServiceTokenNotFoundException} when no token
     * with that id exists in the organization.
     */
    void revoke(UUID tokenId, AlexandriaUserDetails admin);
}
