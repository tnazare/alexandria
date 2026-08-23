package com.alexandria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Configuration properties for the single-org deployment model.
 * In a single-organization deployment, the organization ID is resolved
 * from this configuration rather than from the JWT token.
 *
 * @param defaultId the default organization UUID for user provisioning and data scoping
 */
@ConfigurationProperties(prefix = "alexandria.organization")
public record OrganizationProperties(
        UUID defaultId
) {
}
