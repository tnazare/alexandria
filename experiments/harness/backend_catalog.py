"""Read the resident Skill Catalog from a running Alexandria backend (R9).

The product exposes the catalog as an MCP resource ``alexandria://catalog`` (see the ``skill-catalog``
spec). This module reads that resource over the backend's MCP JSON-RPC endpoint and returns the
rendered catalog text, so the benchmark's catalog condition can source its resident catalog from the
real product path (``--catalog-source backend``) instead of the local ``catalog_server`` simulation
(``--catalog-source local``). Corpus, tasks, and scoring are unchanged — only where the catalog text
comes from differs (R9.3).

Uses only the standard library (mirrors :mod:`harness.alexandria_client`).
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Optional

TIMEOUT = 30
CATALOG_URI = "alexandria://catalog"


class BackendCatalogError(RuntimeError):
    pass


def _rpc(mcp_url: str, bearer: str, payload: dict) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(mcp_url, data=data, method="POST", headers={
        "Authorization": f"Bearer {bearer}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    })
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise BackendCatalogError(f"MCP request failed (HTTP {e.code}): {detail}") from e
    parsed = json.loads(body) if body.strip() else {}
    if isinstance(parsed, dict) and parsed.get("error"):
        raise BackendCatalogError(f"MCP error: {parsed['error']}")
    return parsed


def fetch_backend_catalog_text(mcp_url: str, bearer: str, uri: str = CATALOG_URI) -> str:
    """Read ``alexandria://catalog`` from the backend MCP endpoint and return the rendered text.

    Raises :class:`BackendCatalogError` if the server does not advertise the resource (catalog
    disabled) or returns no text content, so a mislabeled run fails loudly rather than silently
    falling back to an empty catalog.
    """
    resp = _rpc(mcp_url, bearer, {
        "jsonrpc": "2.0", "id": 1, "method": "resources/read",
        "params": {"uri": uri},
    })
    contents = (resp.get("result") or {}).get("contents")
    if not isinstance(contents, list) or not contents:
        raise BackendCatalogError(
            f"backend returned no catalog contents for {uri}; is alexandria.catalog.enabled=true "
            f"on the backend?")
    text = contents[0].get("text")
    if not text:
        raise BackendCatalogError(f"backend catalog resource {uri} had empty text")
    return text


def backend_catalog_available(mcp_url: str, bearer: str) -> bool:
    """True if the backend advertises the catalog resource via resources/list (catalog enabled)."""
    try:
        resp = _rpc(mcp_url, bearer, {
            "jsonrpc": "2.0", "id": 1, "method": "resources/list", "params": {},
        })
    except BackendCatalogError:
        return False
    resources = (resp.get("result") or {}).get("resources") or []
    return any(r.get("uri") == CATALOG_URI for r in resources)
