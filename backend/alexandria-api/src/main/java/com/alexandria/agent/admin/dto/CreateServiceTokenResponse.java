package com.alexandria.agent.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response returned once, at creation, when a service token is minted. This is the only time the
 * plaintext {@code token} secret is ever exposed; the server stores only its SHA-256 hash.
 *
 * @param id          the token's identifier (used to revoke it later)
 * @param name        the human-readable label supplied at creation
 * @param tokenPrefix short non-secret prefix of the secret, for display in listings
 * @param scope       the granted scope (currently always {@code agent_read})
 * @param expiresAt   optional expiry; null when the token does not expire
 * @param token       the full plaintext secret — shown only here, never retrievable again
 */
public record CreateServiceTokenResponse(
        UUID id,
        String name,
        String tokenPrefix,
        String scope,
        Instant expiresAt,
        String token
) {
}
