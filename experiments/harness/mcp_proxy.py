"""Local MCP capture proxy for the Alexandria condition (design §5, Requirement 4.2).

Claude Code is pointed at this proxy instead of Alexandria directly. The proxy forwards every
JSON-RPC message verbatim to the real Alexandria MCP endpoint, and records — for the current task —
the ``resolve_skill`` result candidates, the ``fetch_skill`` ``skillVersionId``, and the wall-clock
of each call at the HTTP boundary. This makes capture independent of whether Claude Code exposes its
internal tool traffic.

The proxy is a tiny stdlib ``http.server`` that runs in a background thread. A :class:`CaptureLog`
collects the per-call observations; the run driver reads it after the Claude invocation returns.

The pure classification/extraction helpers (``classify_message``, ``extract_resolve_candidates``,
``extract_fetch_version_id``) are separated out so they can be unit-tested without a live server.
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional


# --- pure helpers (unit-tested) --------------------------------------------------------------


def classify_message(msg: dict) -> str:
    """Return a coarse kind for a JSON-RPC message: 'tools/call:<tool>', the method, or 'unknown'."""
    method = msg.get("method")
    if method == "tools/call":
        name = (msg.get("params") or {}).get("name")
        return f"tools/call:{name}" if name else "tools/call"
    if isinstance(method, str):
        return method
    return "unknown"


def _tool_result_json(response: dict) -> Optional[dict]:
    """Extract the structured tool result object from a JSON-RPC tools/call response, if present."""
    result = response.get("result")
    if not isinstance(result, dict):
        return None
    # MCP tool results carry structuredContent and/or content[].text (JSON string).
    if isinstance(result.get("structuredContent"), dict):
        return result["structuredContent"]
    content = result.get("content")
    if isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                try:
                    return json.loads(item["text"])
                except (json.JSONDecodeError, KeyError, TypeError):
                    continue
    return None


def extract_resolve_candidates(response: dict) -> Optional[list[str]]:
    """From a resolve_skill tools/call response, return the ordered candidate identifiers.

    The resolver result carries ``name`` (display name) and ``ref.skillVersionId`` (a UUID); it does
    NOT carry a slug. We capture each candidate's ``skillVersionId`` when present, else its ``name``.
    Scoring maps these back to gold slugs using the provision-time id/name -> slug map (the harness
    knows both because it created the skills), so no lossy slug-guessing happens here.
    """
    payload = _tool_result_json(response)
    if not payload:
        return None
    results = payload.get("results")
    if not isinstance(results, list):
        return None
    ids: list[str] = []
    for r in results:
        if not isinstance(r, dict):
            continue
        ref = r.get("ref") or {}
        ident = ref.get("skillVersionId") or r.get("name")
        if ident:
            ids.append(str(ident))
    return ids


def extract_fetch_version_id(msg: dict) -> Optional[str]:
    """From a fetch_skill tools/call request, return the requested skillVersionId argument."""
    if classify_message(msg) != "tools/call:fetch_skill":
        return None
    args = (msg.get("params") or {}).get("arguments") or {}
    vid = args.get("skillVersionId")
    return str(vid) if vid else None


# --- capture log -----------------------------------------------------------------------------


@dataclass
class CaptureLog:
    """Per-task observations collected by the proxy while Claude services one task."""

    resolve_candidates: Optional[list[str]] = None
    fetch_version_id: Optional[str] = None
    resolve_latency_ms: Optional[float] = None
    fetch_latency_ms: Optional[float] = None
    resolve_result_text: Optional[str] = None   # raw resolve response body (for offline token count)
    calls: list[dict[str, Any]] = field(default_factory=list)

    def retrieval_latency_ms(self) -> Optional[float]:
        parts = [x for x in (self.resolve_latency_ms, self.fetch_latency_ms) if x is not None]
        return sum(parts) if parts else None

    def reset(self) -> None:
        self.resolve_candidates = None
        self.fetch_version_id = None
        self.resolve_latency_ms = None
        self.fetch_latency_ms = None
        self.resolve_result_text = None
        self.calls = []


# --- proxy server ----------------------------------------------------------------------------


class McpCaptureProxy:
    """Runs a forwarding MCP proxy on localhost. Point Claude Code's MCP ``url`` at ``endpoint``."""

    def __init__(self, upstream_url: str, bearer_token: str, host: str = "127.0.0.1",
                 port: int = 0) -> None:
        self.upstream_url = upstream_url
        self.bearer_token = bearer_token
        self.capture = CaptureLog()
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

    def _forward(self, body: bytes) -> tuple[int, bytes, float]:
        import httpx

        start = time.perf_counter()
        r = httpx.post(
            self.upstream_url, content=body,
            headers={"Authorization": f"Bearer {self.bearer_token}",
                     "Content-Type": "application/json",
                     "Accept": "application/json, text/event-stream"},
            timeout=60.0,
        )
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return r.status_code, r.content, elapsed_ms

    def _record(self, request_msg: dict, response_bytes: bytes, elapsed_ms: float) -> None:
        kind = classify_message(request_msg)
        self.capture.calls.append({"kind": kind, "elapsed_ms": elapsed_ms})
        if kind == "tools/call:resolve_skill":
            self.capture.resolve_latency_ms = elapsed_ms
            try:
                text = response_bytes.decode("utf-8")
                self.capture.resolve_result_text = text
                self.capture.resolve_candidates = extract_resolve_candidates(json.loads(text))
            except (json.JSONDecodeError, UnicodeDecodeError):
                pass
        elif kind == "tools/call:fetch_skill":
            self.capture.fetch_latency_ms = elapsed_ms
            self.capture.fetch_version_id = extract_fetch_version_id(request_msg)

    def _make_handler(self):
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # silence default logging
                pass

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length) if length else b""
                try:
                    request_msg = json.loads(body.decode("utf-8")) if body else {}
                except (json.JSONDecodeError, UnicodeDecodeError):
                    request_msg = {}
                status, resp_bytes, elapsed = proxy._forward(body)
                try:
                    proxy._record(request_msg, resp_bytes, elapsed)
                except Exception:  # noqa: BLE001 - never fail the proxied call on capture error
                    pass
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                if resp_bytes:
                    self.wfile.write(resp_bytes)

        return Handler
