package com.alexandria.agent.render;

import java.util.regex.Pattern;

/**
 * Conservative sanitizer for skill body content rendered into agent-facing artifacts. The registry
 * has no HTML-sanitizer dependency, so this neutralizes the highest-risk constructs (script blocks,
 * inline event handlers, javascript: URIs) that could execute if a consumer renders the returned
 * Markdown as HTML. It preserves ordinary Markdown/code content otherwise.
 *
 * <p>This is defense-in-depth: skill content is also scanned at authoring time. The goal here is to
 * ensure fetched content cannot inject active HTML/script into a rendering consumer (Req 3.7).</p>
 */
public final class ContentSanitizer {

    private static final Pattern SCRIPT_BLOCK =
            Pattern.compile("(?is)<\\s*script\\b.*?<\\s*/\\s*script\\s*>");
    private static final Pattern SCRIPT_OPEN =
            Pattern.compile("(?is)<\\s*/?\\s*script\\b[^>]*>");
    private static final Pattern EVENT_HANDLER =
            Pattern.compile("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URI =
            Pattern.compile("(?i)javascript:");

    private ContentSanitizer() {
    }

    /**
     * Returns a sanitized copy of the given content, or the input unchanged when null/blank.
     */
    public static String sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String out = SCRIPT_BLOCK.matcher(content).replaceAll("");
        out = SCRIPT_OPEN.matcher(out).replaceAll("");
        out = EVENT_HANDLER.matcher(out).replaceAll("");
        out = JS_URI.matcher(out).replaceAll("");
        return out;
    }
}
