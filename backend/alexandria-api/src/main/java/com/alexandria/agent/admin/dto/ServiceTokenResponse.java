package com.alexandria.agent.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata view of a service token for listings and post-mint reads. Never carries the plaintext
 * secret — only the non-secret display prefix and lifecycle metadata.
 *
 * @param id          the token's identifier
 * @param name        the human-readable label
 * @param tokenPrefix short non-secret prefix of the secret, for display
 * @param scope       the granted scope (currently always {@code agent_read})
 * @param createdBy   the user who minted the token, if known
 * @param createdAt   when the token was minted
 * @param expiresAt   optional expiry; null when the token does not expire
 * @param revokedAt   when the token was revoked; null while active
 * @param lastUsedAt  when the token last authenticated a request; null if never used
 */
public record ServiceTokenResponse(
        UUID id,
        String name,
        String tokenPrefix,
        String scope,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
}
