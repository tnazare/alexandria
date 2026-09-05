package com.alexandria.agent.dto;

import java.util.List;

/**
 * Optional coding context supplied with a resolve request. Used to compute match reasons and,
 * in later phases, to influence ranking. All fields are optional.
 *
 * @param languages      detected repository languages (e.g. ["java"])
 * @param frameworks     detected frameworks (e.g. ["spring_boot", "kafka"])
 * @param tool           the caller's preferred AI tool (e.g. github_copilot)
 * @param activeFilePath the active file path in the editor, if any
 */
public record ResolveContext(
        List<String> languages,
        List<String> frameworks,
        String tool,
        String activeFilePath
) {
}
