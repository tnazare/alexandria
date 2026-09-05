package com.alexandria.agent.dto;

/**
 * The structured content block of a fetched skill version, used by the alexandria_native format.
 * Any field may be null or blank when the underlying version does not provide it.
 *
 * @param canonicalInstructions the version's canonical instructions
 * @param whenToUse             when-to-use guidance
 * @param applicability         applicability notes
 * @param limitations           known limitations
 * @param examples              usage examples
 */
public record NativeContent(
        String canonicalInstructions,
        String whenToUse,
        String applicability,
        String limitations,
        String examples
) {
}
