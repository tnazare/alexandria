"""Native-condition run driver (Requirements 4.1, 4.3, 4.4, 5.2, 5.4, 3.4, 6.4).

Runs one task with Claude Code in the provisioned native workspace (``.claude/skills/`` populated),
then determines which skill the agent used by parsing the transcript for the ``Skill`` dispatcher
tool invocation. Retrieval latency is the measured time to read+parse the matched ``SKILL.md`` from
disk; the one-time frontmatter-preload cost is recorded separately (Requirement 4.3, 4.4).

The transcript-parsing and latency helpers are pure and unit-tested; the ``claude`` subprocess call
is a thin wrapper.
"""

from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path
from typing import Any, Optional

from harness.frontmatter import parse_frontmatter


# --- pure helpers (unit-tested) --------------------------------------------------------------


def extract_chosen_skill(transcript: Any) -> Optional[str]:
    """Return the skill name the agent invoked via the ``Skill`` dispatcher tool, else None.

    Claude Code's ``--output-format json`` yields either a single result object or a list of
    messages, each possibly containing ``content`` blocks. A skill invocation appears as a tool_use
    block whose ``name`` is ``Skill`` (the dispatcher), with the chosen skill in
    ``input.command``/``input.name``/``input.skill``. This scans defensively across shapes.
    """
    for block in _iter_tool_use_blocks(transcript):
        name = block.get("name")
        if name in ("Skill", "skill"):
            inp = block.get("input") or {}
            chosen = inp.get("command") or inp.get("name") or inp.get("skill")
            if chosen:
                # command may be like "pdf" or "pdf:extract"; take the leading token.
                return str(chosen).split(":", 1)[0].strip()
        # Some builds expose the skill directly as the tool name (e.g. "Skill(pdf)").
        if isinstance(name, str) and name.lower().startswith("skill(") and name.endswith(")"):
            return name[len("skill("):-1].strip() or None
    return None


def _iter_tool_use_blocks(transcript: Any):
    """Yield every tool_use block found anywhere in a Claude Code transcript (fully recursive).

    Works for both the single ``--output-format json`` result object and a ``stream-json`` list of
    events (each wrapping ``message.content[]`` blocks). Recurses through dicts/lists so a tool_use
    block is found regardless of nesting depth.
    """
    def walk(node: Any):
        if isinstance(node, dict):
            if node.get("type") in ("tool_use", "server_tool_use"):
                yield node
            for v in node.values():
                yield from walk(v)
        elif isinstance(node, list):
            for v in node:
                yield from walk(v)

    yield from walk(transcript)


def measure_matched_skill_load(skill_md: Path) -> tuple[float, dict]:
    """Read+parse the matched SKILL.md, returning (elapsed_ms, frontmatter).

    This is the native condition's Retrieval_Latency proxy: the cost to bring the chosen skill's
    body into context (Requirement 4.3). Measured on the local filesystem.
    """
    start = time.perf_counter()
    text = skill_md.read_text(encoding="utf-8")
    fm, _ = parse_frontmatter(text)
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    return elapsed_ms, fm


def measure_preload(skills_root: Path) -> tuple[float, int]:
    """Measure the one-time startup cost of reading every skill's frontmatter (Requirement 4.3).

    Returns (elapsed_ms, skill_count). This models Claude's startup preload of name+description for
    all installed skills; recorded separately from per-task retrieval latency.
    """
    start = time.perf_counter()
    count = 0
    for skill_md in skills_root.glob("*/SKILL.md"):
        parse_frontmatter(skill_md.read_text(encoding="utf-8"))
        count += 1
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    return elapsed_ms, count


def parse_claude_json(stdout: str) -> dict:
    """Parse the ``claude -p --output-format json`` stdout into a dict (tolerant of extra lines)."""
    stdout = stdout.strip()
    if not stdout:
        return {}
    try:
        return json.loads(stdout)
    except json.JSONDecodeError:
        # Fall back to the last JSON object on the last non-empty line (streaming formats).
        for line in reversed(stdout.splitlines()):
            line = line.strip()
            if line.startswith("{"):
                try:
                    return json.loads(line)
                except json.JSONDecodeError:
                    continue
        return {}


def parse_claude_stream(stdout: str) -> tuple[list[dict], dict]:
    """Parse ``--output-format stream-json`` stdout into (events, final_result).

    stream-json emits one JSON object per line (system init, assistant/user messages with content
    blocks, and a terminal ``result`` event). Returns all parsed events plus the final ``result``
    event (the summary object, same shape the plain ``json`` format returns). Tolerant of blank or
    non-JSON lines.
    """
    events: list[dict] = []
    final: dict = {}
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(ev, dict):
            continue
        events.append(ev)
        if ev.get("type") == "result":
            final = ev
    return events, final


def reported_usage(result: dict) -> Optional[dict]:
    """Extract Claude's own usage block if present (corroborating token metric, Requirement 3.4)."""
    if isinstance(result.get("usage"), dict):
        return result["usage"]
    if isinstance(result.get("total_usage"), dict):
        return result["total_usage"]
    return None


def reported_model(result: dict) -> Optional[str]:
    """Extract the concrete model version Claude actually used (Requirement 6.4 provenance).

    Prefers a plain ``model`` string; else derives the canonical model from the ``modelUsage`` block
    (choosing the entry with the most input tokens, i.e. the primary model, not a helper like haiku).
    """
    if isinstance(result.get("model"), str):
        return result["model"]
    usage = result.get("modelUsage")
    if isinstance(usage, dict) and usage:
        def _weight(info: object) -> int:
            if not isinstance(info, dict):
                return 0
            # The primary model carries the bulk of context, much of it cache-read; count both plus
            # output. Helper models (e.g. haiku) do small uncached calls, so they weigh less.
            return (int(info.get("inputTokens", 0)) + int(info.get("cacheReadInputTokens", 0))
                    + int(info.get("cacheCreationInputTokens", 0)) + int(info.get("outputTokens", 0)))
        name, info = max(usage.items(), key=lambda kv: _weight(kv[1]))
        if isinstance(info, dict) and info.get("canonicalModel"):
            return str(info["canonicalModel"])
        return str(name)
    return None


# --- subprocess driver -----------------------------------------------------------------------


def run_claude(prompt: str, workspace: Path, *, command: str = "claude",
               model_id: Optional[str] = None, effort: Optional[str] = None, timeout: float = 300.0,
               mcp_config: Optional[Path] = None, allowed_tools: Optional[list[str]] = None,
               disallowed_tools: Optional[list[str]] = None,
               strict_mcp_config: bool = False,
               append_system_prompt: Optional[str] = None,
               stream: bool = False) -> tuple[dict, float]:
    """Invoke ``claude -p`` headless in ``workspace``; return (parsed_result, end_to_end_ms).

    Headless Claude Code does NOT auto-load a project ``.mcp.json`` and denies tool calls without a
    permission decision. So the Alexandria condition passes ``mcp_config`` (+ ``--strict-mcp-config``)
    and ``allowed_tools``; both conditions run with ``--permission-mode bypassPermissions`` so the MCP
    tools / the native ``Skill`` dispatcher can actually fire non-interactively.

    ``model_id`` and ``effort`` are pinned for experiment validity (Requirement 6.4) and recorded in
    ``env.json``. ``effort`` is one of low/medium/high/xhigh/max.

    When ``stream=True``, uses ``--output-format stream-json --verbose`` so tool_use events (the
    native ``Skill`` dispatcher invocation) are observable; the plain ``json`` format only returns the
    final summary and hides tool calls. The returned dict is the final ``result`` event with the full
    event list attached under ``_events`` so ``extract_chosen_skill`` can see the tool_use blocks.
    """
    out_format = "stream-json" if stream else "json"
    args = [command, "-p", prompt, "--output-format", out_format,
            "--permission-mode", "bypassPermissions"]
    if stream:
        args += ["--verbose"]  # required for stream-json in -p mode
    if model_id:
        args += ["--model", model_id]
    if effort:
        args += ["--effort", effort]
    if append_system_prompt:
        args += ["--append-system-prompt", append_system_prompt]
    if mcp_config is not None:
        args += ["--mcp-config", str(mcp_config)]
        if strict_mcp_config:
            args += ["--strict-mcp-config"]
    if allowed_tools:
        args += ["--allowedTools", *allowed_tools]
    if disallowed_tools:
        args += ["--disallowedTools", *disallowed_tools]
    start = time.perf_counter()
    proc = subprocess.run(args, cwd=str(workspace), capture_output=True, text=True, timeout=timeout)
    elapsed_ms = (time.perf_counter() - start) * 1000.0

    if stream:
        events, final = parse_claude_stream(proc.stdout)
        result = dict(final) if final else {}
        result["_events"] = events  # so tool_use blocks are discoverable by extract_chosen_skill
        if not events and proc.returncode != 0:
            result = {"_error": proc.stderr.strip(), "_returncode": proc.returncode}
    else:
        result = parse_claude_json(proc.stdout)
        if not result and proc.returncode != 0:
            result = {"_error": proc.stderr.strip(), "_returncode": proc.returncode}
    return result, elapsed_ms
