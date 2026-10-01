"""Unit tests for the pure parsing/mapping helpers of the run drivers (no subprocess, no server).

Covers the objective-signal extraction the accuracy metrics depend on (Requirement 5.4): MCP
resolve/fetch capture, native transcript skill invocation, and identifier->slug mapping.
"""

from __future__ import annotations

from harness.mcp_proxy import (
    classify_message,
    extract_fetch_version_id,
    extract_resolve_candidates,
)
from harness.run_alexandria import map_candidates_to_slugs
from harness.run_native import (
    extract_chosen_skill,
    parse_claude_json,
    reported_usage,
)


class TestMcpClassify:
    def test_classifies_tools_call_by_tool_name(self):
        assert classify_message({"method": "tools/call", "params": {"name": "resolve_skill"}}) \
            == "tools/call:resolve_skill"
        assert classify_message({"method": "tools/call", "params": {"name": "fetch_skill"}}) \
            == "tools/call:fetch_skill"

    def test_classifies_plain_methods(self):
        assert classify_message({"method": "tools/list"}) == "tools/list"
        assert classify_message({"method": "initialize"}) == "initialize"

    def test_unknown_without_method(self):
        assert classify_message({"id": 1}) == "unknown"


class TestResolveCandidates:
    def test_extracts_display_names_from_structured_content(self):
        # Candidates are captured as display names (the resolver returns name + version id; the
        # harness id-map keys on the display name provisioning created).
        resp = {"result": {"structuredContent": {"results": [
            {"ref": {"skillVersionId": "v-pdf"}, "name": "Pdf"},
            {"ref": {"skillVersionId": "v-xlsx"}, "name": "Xlsx"},
        ]}}}
        assert extract_resolve_candidates(resp) == ["Pdf", "Xlsx"]

    def test_extracts_from_text_content_json(self):
        import json
        payload = {"results": [{"ref": {"skillVersionId": "v-pdf"}, "name": "Pdf"}]}
        resp = {"result": {"content": [{"type": "text", "text": json.dumps(payload)}]}}
        assert extract_resolve_candidates(resp) == ["Pdf"]

    def test_falls_back_to_version_id_when_no_name(self):
        resp = {"result": {"structuredContent": {"results": [{"ref": {"skillVersionId": "v-pdf"}}]}}}
        assert extract_resolve_candidates(resp) == ["v-pdf"]

    def test_none_when_no_results(self):
        assert extract_resolve_candidates({"result": {}}) is None

    def test_versionid_to_name_pairing(self):
        from harness.mcp_proxy import extract_versionid_to_name
        resp = {"result": {"structuredContent": {"results": [
            {"ref": {"skillVersionId": "v-pdf"}, "name": "Pdf"},
            {"ref": {"skillVersionId": "v-xlsx"}, "name": "Xlsx"},
        ]}}}
        assert extract_versionid_to_name(resp) == {"v-pdf": "Pdf", "v-xlsx": "Xlsx"}


class TestFetchVersionId:
    def test_extracts_fetch_version_id(self):
        msg = {"method": "tools/call", "params": {"name": "fetch_skill",
               "arguments": {"skillVersionId": "v-pdf"}}}
        assert extract_fetch_version_id(msg) == "v-pdf"

    def test_none_for_non_fetch(self):
        msg = {"method": "tools/call", "params": {"name": "resolve_skill", "arguments": {}}}
        assert extract_fetch_version_id(msg) is None


class TestNativeTranscript:
    def test_extracts_skill_from_tool_use_command(self):
        transcript = {"messages": [
            {"role": "assistant", "content": [
                {"type": "tool_use", "name": "Skill", "input": {"command": "pdf"}},
            ]},
        ]}
        assert extract_chosen_skill(transcript) == "pdf"

    def test_extracts_skill_from_command_with_subaction(self):
        transcript = [{"content": [
            {"type": "tool_use", "name": "Skill", "input": {"command": "pdf:extract"}},
        ]}]
        assert extract_chosen_skill(transcript) == "pdf"

    def test_extracts_skill_from_named_tool(self):
        transcript = {"content": [{"type": "tool_use", "name": "Skill(xlsx)", "input": {}}]}
        assert extract_chosen_skill(transcript) == "xlsx"

    def test_none_when_no_skill_invoked(self):
        transcript = {"content": [{"type": "tool_use", "name": "Bash", "input": {"command": "ls"}}]}
        assert extract_chosen_skill(transcript) is None

    def test_none_on_empty(self):
        assert extract_chosen_skill({}) is None
        assert extract_chosen_skill([]) is None

    def test_extracts_from_stream_json_shape(self):
        # The real stream-json shape: a tool_use block with name "Skill" and input.skill, nested
        # under an assistant event's message.content, attached as _events on the result.
        from harness.run_native import parse_claude_stream
        lines = "\n".join([
            '{"type":"system","subtype":"init","skills":["pdf","xlsx"]}',
            '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Skill",'
            '"input":{"skill":"pdf"},"id":"t1"}]}}',
            '{"type":"result","result":"done","usage":{"input_tokens":5}}',
        ])
        events, final = parse_claude_stream(lines)
        assert final.get("result") == "done"
        assert len(events) == 3
        result = {"_events": events}
        assert extract_chosen_skill(result) == "pdf"

    def test_stream_parser_tolerates_blank_and_bad_lines(self):
        from harness.run_native import parse_claude_stream
        events, final = parse_claude_stream('\n{"type":"result","result":"x"}\nnot json\n')
        assert final.get("result") == "x"
        assert len(events) == 1


class TestClaudeJson:
    def test_parses_plain_json(self):
        assert parse_claude_json('{"a": 1}') == {"a": 1}

    def test_parses_last_json_line(self):
        assert parse_claude_json('log line\n{"result": "ok"}') == {"result": "ok"}

    def test_empty_on_garbage(self):
        assert parse_claude_json("not json at all") == {}

    def test_reported_usage_variants(self):
        assert reported_usage({"usage": {"input_tokens": 10}}) == {"input_tokens": 10}
        assert reported_usage({"total_usage": {"input_tokens": 5}}) == {"input_tokens": 5}
        assert reported_usage({}) is None


class TestCandidateMapping:
    def test_maps_ids_to_slugs_preserving_order(self):
        id_to_slug = {"v-pdf": "pdf", "v-xlsx": "xlsx", "Pdf": "pdf"}
        assert map_candidates_to_slugs(["v-xlsx", "v-pdf"], id_to_slug) == ["xlsx", "pdf"]

    def test_drops_unknown_ids(self):
        assert map_candidates_to_slugs(["v-pdf", "v-unknown"], {"v-pdf": "pdf"}) == ["pdf"]

    def test_none_passthrough(self):
        assert map_candidates_to_slugs(None, {}) is None
