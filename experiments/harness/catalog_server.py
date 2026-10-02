"""Standalone local MCP server for the catalog condition (no Alexandria backend).

The catalog condition gives Claude a resident Skill Catalog (``id: name — description`` per skill)
in its system prompt, then exposes a **single** tool, ``fetch_skill(id)``, that returns one skill's
SKILL.md body. Unlike the Alexandria condition's :mod:`mcp_proxy` (which forwards to a running
server), this server is fully self-contained: it answers the MCP JSON-RPC handshake itself and reads
bodies straight from the corpus files. That keeps the catalog A/B test runnable with **no stack and
no API spend beyond the Claude turn** — the only moving part is the one fetch.

It implements the minimum of the Streamable-HTTP MCP contract that headless Claude Code exercises:
``initialize``, ``notifications/initialized`` (no-op), ``tools/list`` (advertises ``fetch_skill``),
and ``tools/call`` for ``fetch_skill``. Each ``fetch_skill`` call records the requested id and the
server-side handling latency into a :class:`CatalogCapture`, which the run driver reads back.

The pure request-handling (``handle_message``) and body lookup (``load_body``) are separated from the
HTTP/threading shell so they unit-test without a live socket.
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional

from harness.measure_tokens import FETCH_BY_ID_SCHEMA
from harness.models import CorpusManifest

PROTOCOL_VERSION = "2025-06-18"

_FETCH_TOOL = {
    "name": "fetch_skill",
    "description": (
        "Fetch the full instructions for one skill chosen from the resident Skill Catalog. "
        "Pass the catalog id of the skill you selected."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {"id": {"type": "string", "description": "Catalog id (slug) of the skill."}},
        "required": ["id"],
    },
}


@dataclass
class CatalogCapture:
    """Per-task observations: which id was fetched and the handling latency."""

    fetched_id: Optional[str] = None
    fetch_latency_ms: Optional[float] = None
    calls: list[dict[str, Any]] = field(default_factory=list)

    def retrieval_latency_ms(self) -> Optional[float]:
        return self.fetch_latency_ms

    def reset(self) -> None:
        self.fetched_id = None
        self.fetch_latency_ms = None
        self.calls = []


def load_body(corpus_dir: Path, manifest: CorpusManifest, skill_id: str) -> Optional[str]:
    """Return the SKILL.md body for ``skill_id`` (a slug) from the corpus, or None if unknown."""
    entry = next((e for e in manifest.skills if e.slug == skill_id), None)
    if entry is None:
        return None
    skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
    if not skill_md.is_file():
        return None
    return skill_md.read_text(encoding="utf-8")


def _rpc_result(msg_id: Any, result: dict) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _rpc_error(msg_id: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def handle_message(msg: dict, corpus_dir: Path, manifest: CorpusManifest,
                   capture: CatalogCapture) -> Optional[dict]:
    """Handle one JSON-RPC request; return the response dict (or None for notifications).

    Pure except for the corpus read in ``fetch_skill`` and recording into ``capture`` — no sockets,
    so it is directly unit-testable.
    """
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        return _rpc_result(msg_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "catalog-fetch", "version": "1.0.0"},
        })
    if method in ("notifications/initialized", "initialized"):
        return None  # notification: no response
    if method == "tools/list":
        return _rpc_result(msg_id, {"tools": [_FETCH_TOOL]})
    if method == "tools/call":
        params = msg.get("params") or {}
        if params.get("name") != "fetch_skill":
            return _rpc_error(msg_id, -32601, f"unknown tool: {params.get('name')}")
        start = time.perf_counter()
        skill_id = str((params.get("arguments") or {}).get("id") or "").strip()
        body = load_body(corpus_dir, manifest, skill_id)
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        capture.fetched_id = skill_id or None
        capture.fetch_latency_ms = elapsed_ms
        capture.calls.append({"kind": "tools/call:fetch_skill", "id": skill_id,
                              "elapsed_ms": elapsed_ms})
        if body is None:
            return _rpc_result(msg_id, {
                "content": [{"type": "text", "text": f"No skill with id '{skill_id}'."}],
                "isError": True,
            })
        return _rpc_result(msg_id, {"content": [{"type": "text", "text": body}]})

    return _rpc_error(msg_id, -32601, f"method not found: {method}")


class CatalogServer:
    """Runs the self-contained catalog fetch MCP server on localhost. No upstream backend."""

    def __init__(self, corpus_dir: Path, manifest: CorpusManifest, host: str = "127.0.0.1",
                 port: int = 0) -> None:
        self.corpus_dir = Path(corpus_dir)
        self.manifest = manifest
        self.capture = CatalogCapture()
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
            def log_message(self, *args):  # silence default logging
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
                                              server.capture)
                except Exception as e:  # noqa: BLE001 - surface as JSON-RPC error, never crash
                    response = _rpc_error(msg.get("id"), -32603, f"internal error: {e}")
                if response is None:
                    self.send_response(202)  # accepted notification, no body
                    self.end_headers()
                    return
                payload = json.dumps(response).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        return Handler
