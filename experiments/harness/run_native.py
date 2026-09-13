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
    """Yield every tool_use content block found anywhere in a Claude Code JSON transcript."""
    messages: list[Any] = []
    if isinstance(transcript, dict):
        # A single result object may hold messages under common keys, or be a message itself.
        for key in ("messages", "transcript", "output"):
            if isinstance(transcript.get(key), list):
                messages.extend(transcript[key])
        messages.append(transcript)
    elif isinstance(transcript, list):
        messages.extend(transcript)

    for msg in messages:
        if not isinstance(msg, dict):
            continue
        content = msg.get("content")
        if isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") in ("tool_use", "server_tool_use"):
                    yield block


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


def reported_usage(result: dict) -> Optional[dict]:
    """Extract Claude's own usage block if present (corroborating token metric, Requirement 3.4)."""
    if isinstance(result.get("usage"), dict):
        return result["usage"]
    if isinstance(result.get("total_usage"), dict):
        return result["total_usage"]
    return None


# --- subprocess driver -----------------------------------------------------------------------


def run_claude(prompt: str, workspace: Path, *, command: str = "claude",
               model_id: Optional[str] = None, timeout: float = 300.0) -> tuple[dict, float]:
    """Invoke ``claude -p`` headless in ``workspace``; return (parsed_result, end_to_end_ms)."""
    args = [command, "-p", prompt, "--output-format", "json"]
    if model_id:
        args += ["--model", model_id]
    start = time.perf_counter()
    proc = subprocess.run(args, cwd=str(workspace), capture_output=True, text=True, timeout=timeout)
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    result = parse_claude_json(proc.stdout)
    if not result and proc.returncode != 0:
        result = {"_error": proc.stderr.strip(), "_returncode": proc.returncode}
    return result, elapsed_ms
