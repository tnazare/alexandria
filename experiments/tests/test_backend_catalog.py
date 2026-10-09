"""Unit tests for harness.backend_catalog — reading the alexandria://catalog MCP resource.

Mocks the HTTP transport so parsing of the JSON-RPC resources/read and resources/list responses is
tested without a live backend.
"""

from __future__ import annotations

import io
import json

import pytest

import harness.backend_catalog as bc


def _fake_urlopen_factory(response_obj):
    def _fake_urlopen(req, timeout=None):
        body = json.dumps(response_obj).encode("utf-8")
        resp = io.BytesIO(body)
        resp.status = 200
        resp.__enter__ = lambda s: s
        resp.__exit__ = lambda s, *a: False
        return resp
    return _fake_urlopen


def test_fetch_backend_catalog_text_parses_contents(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1,
        "result": {"contents": [{"uri": "alexandria://catalog", "mimeType": "text/markdown",
                                 "text": "# Skill Catalog\nvid: A — b\n"}]},
    }))
    text = bc.fetch_backend_catalog_text("http://localhost:8080/api/agent/v1/mcp", "bearer")
    assert "# Skill Catalog" in text
    assert "vid: A \u2014 b" in text


def test_fetch_raises_when_no_contents(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1, "result": {"contents": []},
    }))
    with pytest.raises(bc.BackendCatalogError):
        bc.fetch_backend_catalog_text("http://localhost:8080/api/agent/v1/mcp", "bearer")


def test_fetch_raises_on_jsonrpc_error(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1, "error": {"code": -32601, "message": "Method not found"},
    }))
    with pytest.raises(bc.BackendCatalogError):
        bc.fetch_backend_catalog_text("http://localhost:8080/api/agent/v1/mcp", "bearer")


def test_backend_catalog_available_true_when_advertised(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1,
        "result": {"resources": [{"uri": "alexandria://catalog", "name": "Skill Catalog"}]},
    }))
    assert bc.backend_catalog_available("http://localhost:8080/api/agent/v1/mcp", "bearer") is True


def test_backend_catalog_available_false_when_absent(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1, "result": {"resources": []},
    }))
    assert bc.backend_catalog_available("http://localhost:8080/api/agent/v1/mcp", "bearer") is False
