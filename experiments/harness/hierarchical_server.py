"""Standalone local MCP server for the hierarchical-disclosure condition (no Alexandria backend).

The hierarchical condition gives Claude a resident **Category Index** (``label (count): summary`` per
category) in its system prompt, then exposes **two** tools:

- ``list_category(category)`` — discloses one category's skills as ``id: name — description`` lines.
- ``fetch_skill(id)`` — returns one skill's SKILL.md body.

So the agent navigates: resident headers → open one category → pick a skill → fetch it. Only the
category headers are resident (O(C)); the per-skill lines are disclosed one category at a time. Like
:mod:`harness.catalog_server`, this is fully self-contained — it answers the MCP JSON-RPC handshake
itself and reads lines/bodies from the corpus + the committed categorization, so the A/B runs with no
stack and only the Claude turn costs anything.

Capture records, per task: which categories were opened (``opened_categories``), the fetched id
(``fetched_id``), and per-call latency — so compliance (did it engage the index at all), model-choice
(the fetched slug), and latency are measured directly, exactly like the other conditions.

The pure handler (``handle_message``) and lookups are separated from the HTTP/threading shell so they
unit-test without a live socket.
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional

from harness.catalog_server import load_body
from harness.measure_tokens import catalog_line
from harness.models import CorpusManifest

PROTOCOL_VERSION = "2025-06-18"

_LIST_TOOL = {
    "name": "list_category",
    "description": (
        "List the skills in one category from the resident Category Index. Pass the category label; "
        "returns that category's skills as 'id: name — description' lines."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {"category": {"type": "string", "description": "Category label to open."}},
        "required": ["category"],
    },
}

_FETCH_TOOL = {
    "name": "fetch_skill",
    "description": (
        "Fetch the full instructions for one skill chosen after listing its category. Pass the "
        "skill's catalog id."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {"id": {"type": "string", "description": "Catalog id (slug) of the skill."}},
        "required": ["id"],
    },
}


@dataclass
class HierarchicalCapture:
    """Per-task observations: categories opened, the fetched id, and latencies."""

    opened_categories: list[str] = field(default_factory=list)
    fetched_id: Optional[str] = None
    list_latency_ms: Optional[float] = None     # total across list_category calls
    fetch_latency_ms: Optional[float] = None
    calls: list[dict[str, Any]] = field(default_factory=list)

    def retrieval_latency_ms(self) -> Optional[float]:
        parts = [x for x in (self.list_latency_ms, self.fetch_latency_ms) if x is not None]
        return sum(parts) if parts else None

    def reset(self) -> None:
        self.opened_categories = []
        self.fetched_id = None
        self.list_latency_ms = None
        self.fetch_latency_ms = None
        self.calls = []


def _rpc_result(msg_id: Any, result: dict) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _rpc_error(msg_id: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def _category_lines(label: str, label_to_slugs: dict[str, list[str]],
                    corpus_dir: Path, manifest: CorpusManifest, tier_value: str) -> Optional[str]:
    """Render one category's ``id: name — description`` lines, or None if the label is unknown."""
    from harness.frontmatter import parse_frontmatter

    slugs = label_to_slugs.get(label)
    if slugs is None:
        return None
    lines: list[str] = []
    for slug in slugs:
        p = corpus_dir / tier_value / slug / "SKILL.md"
        name, desc = slug, ""
        if p.is_file():
            fm, _ = parse_frontmatter(p.read_text(encoding="utf-8"))
            name = str(fm.get("name") or slug)
            desc = str(fm.get("description") or "")
        lines.append(catalog_line(slug, name, desc))
    return "\n".join(lines)


def handle_message(msg: dict, corpus_dir: Path, manifest: CorpusManifest,
                   label_to_slugs: dict[str, list[str]], capture: HierarchicalCapture,
                   tier_value: str = "real") -> Optional[dict]:
    """Handle one JSON-RPC request; return the response dict (None for notifications)."""
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        return _rpc_result(msg_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "hierarchical-disclosure", "version": "1.0.0"},
        })
    if method in ("notifications/initialized", "initialized"):
        return None
    if method == "tools/list":
        return _rpc_result(msg_id, {"tools": [_LIST_TOOL, _FETCH_TOOL]})
    if method == "tools/call":
        params = msg.get("params") or {}
        name = params.get("name")
        args = params.get("arguments") or {}

        if name == "list_category":
            start = time.perf_counter()
            label = str(args.get("category") or "").strip()
            lines = _category_lines(label, label_to_slugs, corpus_dir, manifest, tier_value)
            elapsed = (time.perf_counter() - start) * 1000.0
            capture.opened_categories.append(label)
            capture.list_latency_ms = (capture.list_latency_ms or 0.0) + elapsed
            capture.calls.append({"kind": "tools/call:list_category", "category": label,
                                  "elapsed_ms": elapsed})
            if lines is None:
                known = ", ".join(sorted(label_to_slugs)[:12])
                return _rpc_result(msg_id, {
                    "content": [{"type": "text",
                                 "text": f"No category '{label}'. Known categories: {known}"}],
                    "isError": True,
                })
            return _rpc_result(msg_id, {"content": [{"type": "text", "text": lines}]})

        if name == "fetch_skill":
            start = time.perf_counter()
            skill_id = str(args.get("id") or "").strip()
            body = load_body(corpus_dir, manifest, skill_id)
            elapsed = (time.perf_counter() - start) * 1000.0
            capture.fetched_id = skill_id or None
            capture.fetch_latency_ms = elapsed
            capture.calls.append({"kind": "tools/call:fetch_skill", "id": skill_id,
                                  "elapsed_ms": elapsed})
            if body is None:
                return _rpc_result(msg_id, {
                    "content": [{"type": "text", "text": f"No skill with id '{skill_id}'."}],
                    "isError": True,
                })
            return _rpc_result(msg_id, {"content": [{"type": "text", "text": body}]})

        return _rpc_error(msg_id, -32601, f"unknown tool: {name}")

    return _rpc_error(msg_id, -32601, f"method not found: {method}")


class HierarchicalServer:
    """Runs the self-contained hierarchical-disclosure MCP server on localhost. No backend."""

    def __init__(self, corpus_dir: Path, manifest: CorpusManifest,
                 label_to_slugs: dict[str, list[str]], tier_value: str = "real",
                 host: str = "127.0.0.1", port: int = 0) -> None:
        self.corpus_dir = Path(corpus_dir)
        self.manifest = manifest
        self.label_to_slugs = label_to_slugs
        self.tier_value = tier_value
        self.capture = HierarchicalCapture()
        self._server = ThreadingHTTPServer((host, port), self._make_handler())
        self._thread: Optional[threading.Thread] = None

    @property
    def endpoint(self) -> str:
        host, port = self._server.server_address
        return f"http://{host}:{port}/mcp"

    def start(self) -> None:
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        if self._thread:
            self._thread.join(timeout=5)

    def _make_handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b""
                try:
                    msg = json.loads(raw.decode("utf-8")) if raw else {}
                except (json.JSONDecodeError, UnicodeDecodeError):
                    msg = {}
                try:
                    response = handle_message(msg, server.corpus_dir, server.manifest,
                                              server.label_to_slugs, server.capture,
                                              server.tier_value)
                except Exception as e:  # noqa: BLE001
                    response = _rpc_error(msg.get("id"), -32603, f"internal error: {e}")
                if response is None:
                    self.send_response(202)
                    self.end_headers()
                    return
                payload = json.dumps(response).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        return Handler
