package com.alexandria.skill.export;

/**
 * Small shared helpers for renderers. Pure and deterministic.
 */
final class RenderSupport {

    private RenderSupport() {
    }

    /** True when the value is non-null and contains non-whitespace. */
    static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** Returns the trimmed value, or the empty string when null/blank. */
    static String orEmpty(String value) {
        return present(value) ? value.strip() : "";
    }

    /**
     * Appends a Markdown section (`## heading` + body) when the body is present.
     * The body is emitted as plain Markdown text (the storage format), not fenced.
     */
    static void section(StringBuilder sb, String heading, String body) {
        if (present(body)) {
            sb.append("## ").append(heading).append("\n\n");
            sb.append(body.strip()).append("\n\n");
        }
    }
}
