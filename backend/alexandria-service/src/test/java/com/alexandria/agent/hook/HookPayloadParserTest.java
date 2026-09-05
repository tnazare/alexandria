package com.alexandria.agent.hook;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HookPayloadParser} across both harness payload shapes (Requirement 11.2).
 */
@Tag("skill-resolver")
class HookPayloadParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HookPayloadParser parser = new HookPayloadParser();

    @Test
    void parsesClaudeCodePascalCaseUserPromptSubmit() {
        var node = mapper.readTree("""
                {"hook_event_name":"UserPromptSubmit","session_id":"abc","prompt":"do the thing"}
                """);

        HookInvocation inv = parser.parse(node);

        assertThat(inv.harness()).isEqualTo(HookInvocation.HARNESS_CLAUDE);
        assertThat(inv.event()).isEqualTo(HookInvocation.EVENT_USER_PROMPT);
        assertThat(inv.sessionId()).isEqualTo("abc");
        assertThat(inv.prompt()).isEqualTo("do the thing");
    }

    @Test
    void parsesCopilotCamelCaseUserPromptSubmitted() {
        var node = mapper.readTree("""
                {"event":"userPromptSubmitted","sessionId":"xyz","prompt":"review this"}
                """);

        HookInvocation inv = parser.parse(node);

        assertThat(inv.harness()).isEqualTo(HookInvocation.HARNESS_COPILOT);
        assertThat(inv.event()).isEqualTo(HookInvocation.EVENT_USER_PROMPT);
        assertThat(inv.sessionId()).isEqualTo("xyz");
        assertThat(inv.prompt()).isEqualTo("review this");
    }

    @Test
    void parsesPreToolUseWithToolName() {
        var node = mapper.readTree("""
                {"hook_event_name":"PreToolUse","session_id":"s","tool_name":"Bash"}
                """);

        HookInvocation inv = parser.parse(node);

        assertThat(inv.event()).isEqualTo(HookInvocation.EVENT_PRE_TOOL_USE);
        assertThat(inv.toolName()).isEqualTo("Bash");
    }

    @Test
    void parsesSessionStart() {
        var node = mapper.readTree("""
                {"hook_event_name":"SessionStart","session_id":"s","source":"startup"}
                """);

        assertThat(parser.parse(node).event()).isEqualTo(HookInvocation.EVENT_SESSION_START);
    }

    @Test
    void unknownEventNormalizesToOther() {
        var node = mapper.readTree("""
                {"hook_event_name":"Notification","message":"hi"}
                """);

        assertThat(parser.parse(node).event()).isEqualTo(HookInvocation.EVENT_OTHER);
    }
}
