package com.alexandria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the agent-facing Skill Resolver. All values are environment-variable driven so
 * the resolver can be deployed without source changes.
 *
 * @param instanceUrl        base URL of this Alexandria instance, embedded as {@code source_instance}
 *                           in every resolver artifact so committed skills stay traceable
 *                           (env {@code ALEXANDRIA_INSTANCE_URL})
 * @param maxLimit           maximum resolve candidates per request (env {@code RESOLVER_MAX_LIMIT})
 * @param defaultLimit       default resolve candidates when the request omits a limit
 * @param defaultReferenceMode default reference mode when the request omits one
 *                           (env {@code RESOLVER_DEFAULT_REFERENCE_MODE})
 */
@ConfigurationProperties(prefix = "alexandria.resolver")
public record ResolverProperties(
        String instanceUrl,
        Integer maxLimit,
        Integer defaultLimit,
        String defaultReferenceMode,
        Integer hookMaxInjectBytes,
        Integer hookMinPromptChars,
        Boolean hookInjectOnPrompt
) {

    public ResolverProperties {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            instanceUrl = "http://localhost:8080";
        }
        if (maxLimit == null || maxLimit < 1) {
            maxLimit = 25;
        }
        if (defaultLimit == null || defaultLimit < 1) {
            defaultLimit = 5;
        }
        if (defaultReferenceMode == null || defaultReferenceMode.isBlank()) {
            defaultReferenceMode = "floating_latest_approved";
        }
        if (hookMaxInjectBytes == null || hookMaxInjectBytes < 1) {
            hookMaxInjectBytes = 8192;
        }
        if (hookMinPromptChars == null || hookMinPromptChars < 0) {
            hookMinPromptChars = 12;
        }
        if (hookInjectOnPrompt == null) {
            // Default trigger (Task 7.0): inject the resolved skill on prompt submission. Deployers
            // can disable to fall back to a lighter sessionStart-seeding-only posture.
            hookInjectOnPrompt = true;
        }
    }
}
