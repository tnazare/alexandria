package com.alexandria.agent.hook;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Parses a raw hook JSON payload into a normalized {@link HookInvocation}, tolerating the field
 * conventions of both supported harnesses:
 * <ul>
 *   <li>Claude Code / VS Code compatible: PascalCase event {@code hook_event_name} (e.g.
 *       {@code UserPromptSubmit}, {@code PreToolUse}, {@code SessionStart}) with snake_case fields
 *       ({@code session_id}, {@code prompt}, {@code tool_name}).</li>
 *   <li>Copilot camelCase: {@code userPromptSubmitted}, {@code preToolUse}, {@code sessionStart}
 *       with camelCase fields ({@code sessionId}, {@code prompt}, {@code toolName}).</li>
 * </ul>
 *
 * <p>The event name is taken from {@code hook_event_name}/{@code hookEventName} when present, else
 * inferred from a top-level {@code event}/{@code type} field.</p>
 */
@Component
public class HookPayloadParser {

    public HookInvocation parse(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) {
            return new HookInvocation(HookInvocation.HARNESS_CLAUDE, HookInvocation.EVENT_OTHER,
                    null, null, null);
        }

        String rawEvent = firstText(payload, "hook_event_name", "hookEventName", "event", "type");
        String event = normalizeEvent(rawEvent);
        boolean pascal = rawEvent != null && !rawEvent.isEmpty() && Character.isUpperCase(rawEvent.charAt(0));
        String harness = pascal ? HookInvocation.HARNESS_CLAUDE : HookInvocation.HARNESS_COPILOT;

        String sessionId = firstText(payload, "session_id", "sessionId");
        String prompt = firstText(payload, "prompt", "initial_prompt", "initialPrompt");
        String toolName = firstText(payload, "tool_name", "toolName");

        return new HookInvocation(harness, event, sessionId, prompt, toolName);
    }

    private String normalizeEvent(String raw) {
        if (raw == null) {
            return HookInvocation.EVENT_OTHER;
        }
        String r = raw.toLowerCase().replace("_", "");
        if (r.startsWith("userprompt")) {
            return HookInvocation.EVENT_USER_PROMPT;
        }
        if (r.startsWith("sessionstart")) {
            return HookInvocation.EVENT_SESSION_START;
        }
        if (r.startsWith("pretooluse")) {
            return HookInvocation.EVENT_PRE_TOOL_USE;
        }
        return HookInvocation.EVENT_OTHER;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.get(field);
            if (v != null && v.isValueNode() && !v.asText("").isBlank()) {
                return v.asText("");
            }
        }
        return null;
    }
}
