package com.alexandria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for OIDC resource server integration.
 *
 * @param issuerUri the OIDC issuer URI used to validate JWT tokens
 * @param clientId  the expected audience (client ID) in JWT tokens
 */
@ConfigurationProperties(prefix = "alexandria.oidc")
public record OidcProperties(
        String issuerUri,
        String clientId
) {
}
