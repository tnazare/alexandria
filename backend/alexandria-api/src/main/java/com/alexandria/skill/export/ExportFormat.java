package com.alexandria.skill.export;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A target output format for rendering a skill (Skill Export milestone).
 *
 * <p>Each value carries its wire value (the {@code format} query parameter) and a download
 * filename pattern. {@code {slug}} in a pattern is substituted with the skill slug by the
 * client that performs the download.</p>
 */
public enum ExportFormat {

    GENERIC_MARKDOWN("generic_markdown", "{slug}.md"),
    GITHUB_COPILOT("github_copilot", "{slug}.copilot.md"),
    CODEX("codex", "{slug}.codex.md"),
    AGENTS_MD("agents_md", "AGENTS.md"),
    CURSOR_RULE("cursor_rule", ".cursorrules"),
    KIRO_STEERING("kiro_steering", "{slug}-steering.md");

    private final String wireValue;
    private final String filenamePattern;

    ExportFormat(String wireValue, String filenamePattern) {
        this.wireValue = wireValue;
        this.filenamePattern = filenamePattern;
    }

    public String wireValue() {
        return wireValue;
    }

    public String filenamePattern() {
        return filenamePattern;
    }

    /**
     * Resolves an {@link ExportFormat} from its wire value, case-insensitively.
     *
     * @param value the {@code format} query parameter
     * @return the matching format
     * @throws IllegalArgumentException if the value is null or unsupported (message lists
     *                                  the supported formats)
     */
    public static ExportFormat fromWire(String value) {
        if (value != null) {
            for (ExportFormat format : values()) {
                if (format.wireValue.equalsIgnoreCase(value.trim())) {
                    return format;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unsupported export format: '" + value + "'. Supported formats: " + supportedWireValues());
    }

    private static String supportedWireValues() {
        return Arrays.stream(values())
                .map(ExportFormat::wireValue)
                .collect(Collectors.joining(", "));
    }
}
