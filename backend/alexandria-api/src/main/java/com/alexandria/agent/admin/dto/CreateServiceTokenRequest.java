package com.alexandria.agent.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Request to mint a new service token.
 *
 * @param name      required human-readable label for the token (shown in listings)
 * @param expiresAt optional expiry; null means the token does not expire
 */
public record CreateServiceTokenRequest(
        @NotBlank String name,
        Instant expiresAt
) {
}
