package com.alexandria.agent.dto;

/**
 * The tool-specific instructions selected for a fetch, or the canonical fallback when no variant
 * exists for the requested tool.
 *
 * @param tool               the tool identifier the instructions target (or "generic"/canonical)
 * @param instructions       the instruction text
 * @param isCanonicalFallback true when no variant matched the requested tool and the canonical
 *                            instructions were returned instead
 */
public record NativeVariant(
        String tool,
        String instructions,
        boolean isCanonicalFallback
) {
}
