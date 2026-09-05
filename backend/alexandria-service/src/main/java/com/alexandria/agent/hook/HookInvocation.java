package com.alexandria.agent.hook;

/**
 * Normalized view of an incoming agent lifecycle hook, parsed from either the Copilot payload shape
 * (camelCase {@code userPromptSubmitted} / snake_case VS Code form) or the Claude Code shape
 * (PascalCase {@code UserPromptSubmit}).
 *
 * @param harness   the detected harness family ({@code copilot} or {@code claude_code})
 * @param event     the normalized event name ({@code user_prompt_submit}, {@code session_start},
 *                  {@code pre_tool_use}, or {@code other})
 * @param sessionId the harness session id, used for per-session caching (may be null)
 * @param prompt    the submitted prompt text for prompt events (may be null)
 * @param toolName  the tool name for pre-tool-use events (may be null)
 */
public record HookInvocation(
        String harness,
        String event,
        String sessionId,
        String prompt,
        String toolName
) {
    public static final String HARNESS_COPILOT = "copilot";
    public static final String HARNESS_CLAUDE = "claude_code";

    public static final String EVENT_USER_PROMPT = "user_prompt_submit";
    public static final String EVENT_SESSION_START = "session_start";
    public static final String EVENT_PRE_TOOL_USE = "pre_tool_use";
    public static final String EVENT_OTHER = "other";
}
