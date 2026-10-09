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


def test_parse_versionid_name_pairs_extracts_id_and_name():
    text = (
        "# Skill Catalog\n"
        "These are reusable governed skills, one per line as `id: name — description`. ...\n"
        "498ddb14-c653-47b4-b49d-ae96dc70109f: Ab Test Setup \u2014 Structured guide for A/B tests.\n"
        "7f2a1b33-0000-4000-8000-000000000001: Web Scraper \u2014 Scrape pages reliably.\n"
        "_Catalog truncated to 2 entries._\n"
    )
    pairs = bc.parse_versionid_name_pairs(text)
    assert pairs == [
        ("498ddb14-c653-47b4-b49d-ae96dc70109f", "Ab Test Setup"),
        ("7f2a1b33-0000-4000-8000-000000000001", "Web Scraper"),
    ]


def test_parse_versionid_name_pairs_skips_header_and_prose():
    # The header sentence contains ": " but its id-part has spaces, so it is skipped.
    text = "These are reusable governed skills: pick one.\nvid-1: Name \u2014 desc\n"
    assert bc.parse_versionid_name_pairs(text) == [("vid-1", "Name")]


def test_backend_catalog_available_false_when_absent(monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen_factory({
        "jsonrpc": "2.0", "id": 1, "result": {"resources": []},
    }))
    assert bc.backend_catalog_available("http://localhost:8080/api/agent/v1/mcp", "bearer") is False
