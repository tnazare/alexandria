package com.alexandria.skill.export;

/**
 * Builds Markdown code fences that cannot be terminated early by their own content.
 *
 * <p>A CommonMark fenced code block is closed by a backtick run at least as long as its
 * opening run. To embed arbitrary content safely, the opening/closing fence must use a run
 * strictly longer than the longest backtick run appearing in the content (Property 8).</p>
 */
public final class MarkdownFence {

    private static final int MIN_FENCE = 3;

    private MarkdownFence() {
    }

    /**
     * Wraps content in a Markdown code fence whose backtick run is longer than any run in the
     * content, so the content cannot close the fence prematurely.
     *
     * @param content the content to fence (null is treated as empty)
     * @return the fenced block, ending with a newline
     */
    public static String fence(String content) {
        String body = content == null ? "" : content;
        int fenceLength = Math.max(MIN_FENCE, longestBacktickRun(body) + 1);
        String fence = "`".repeat(fenceLength);
        StringBuilder sb = new StringBuilder();
        sb.append(fence).append('\n');
        sb.append(body);
        if (!body.isEmpty() && !body.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(fence).append('\n');
        return sb.toString();
    }

    /** Returns the length of the longest consecutive run of backticks in the text. */
    static int longestBacktickRun(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
