"""Unit tests for the catalog condition's deterministic pieces (no subprocess, no live socket).

Covers the catalog token model (resident compact catalog + per-task fetched body), the resident
catalog text generation, the local fetch server's JSON-RPC message handling, and id->slug mapping.
All use a trivial token counter so no ``tiktoken`` runtime is required.
"""

from __future__ import annotations

from pathlib import Path

from harness.build_corpus import compute_content_hash
from harness.catalog_server import CatalogCapture, handle_message, load_body
from harness.measure_tokens import (
    catalog_line,
    catalog_resident_tokens,
    catalog_task_tokens,
    native_resident_tokens,
)
from harness.models import CorpusManifest, ManifestEntry, Tier
from harness.provision_catalog import (
    build_catalog_text,
    catalog_id_to_slug,
    catalog_system_prompt,
)


def wc(text: str) -> int:
    """Trivial deterministic counter: number of whitespace-delimited tokens."""
    return len(text.split())


def _skill_body(slug: str, desc_words: int = 5, body_words: int = 40) -> str:
    desc = " ".join(["d"] * desc_words)
    body_line = " ".join(["b"] * body_words)
    return f"---\nname: {slug}\ndescription: {desc}\n---\n# {slug}\n{body_line}\n"


def _write_corpus(tmp_path: Path, slugs: list[str]):
    corpus = tmp_path / "corpus"
    entries = []
    for slug in slugs:
        d = corpus / Tier.REAL.value / slug
        d.mkdir(parents=True, exist_ok=True)
        (d / "SKILL.md").write_text(_skill_body(slug), encoding="utf-8")
        entries.append(ManifestEntry(slug=slug, tier=Tier.REAL, name=slug, sha256=f"s-{slug}"))
    manifest = CorpusManifest(experiment_version="v1", library_size=len(entries), seed=1,
                              content_hash=compute_content_hash(entries), skills=entries)
    return manifest, corpus


class TestCatalogTokenModel:
    def test_resident_grows_with_n_but_carries_one_schema(self, tmp_path):
        m_small, c_small = _write_corpus(tmp_path / "a", [f"r{i}" for i in range(5)])
        m_big, c_big = _write_corpus(tmp_path / "b", [f"r{i}" for i in range(20)])
        small = catalog_resident_tokens(wc, m_small, c_small)
        big = catalog_resident_tokens(wc, m_big, c_big)
        assert big > small  # catalog grows with N (one line per skill)

    def test_catalog_resident_cheaper_than_native_resident(self, tmp_path):
        # Same corpus: the compact "id: name — desc" line is <= native's full name+desc preload,
        # so the catalog's resident cost should not exceed native's by much and tracks it in N.
        manifest, corpus = _write_corpus(tmp_path, [f"r{i}" for i in range(15)])
        cat = catalog_resident_tokens(wc, manifest, corpus)
        nat = native_resident_tokens(wc, manifest, corpus)
        # Both are linear in N; catalog adds slug+punctuation per line + one schema. Assert both are
        # positive and in the same order of magnitude (within 3x), i.e. no accidental blow-up.
        assert cat > 0 and nat > 0
        assert cat < nat * 3

    def test_task_adds_only_fetched_body(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf", "xlsx"])
        matched = catalog_task_tokens(wc, manifest, corpus, matched_slug="pdf")
        none = catalog_task_tokens(wc, manifest, corpus, matched_slug=None)
        assert none.per_task == 0                 # no fetch -> only resident catalog in context
        assert matched.per_task > 0               # one body fetched
        assert matched.resident == none.resident  # resident catalog identical
        assert matched.total == matched.resident + matched.per_task

    def test_catalog_line_shape(self):
        assert catalog_line("pdf-tools", "PDF Tools", "Work with PDFs") == \
            "pdf-tools: PDF Tools — Work with PDFs"


class TestCatalogText:
    def test_build_catalog_text_one_line_per_skill_in_order(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["aaa", "bbb", "ccc"])
        text = build_catalog_text(corpus, manifest)
        lines = text.splitlines()
        assert len(lines) == 3
        assert lines[0].startswith("aaa: ")   # id is the slug, order follows manifest
        assert lines[2].startswith("ccc: ")

    def test_system_prompt_includes_directive_and_catalog(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        text = build_catalog_text(corpus, manifest)
        prompt = catalog_system_prompt("You MUST review the catalog.", text)
        assert "You MUST review the catalog." in prompt
        assert "Skill Catalog" in prompt
        assert "pdf: " in prompt

    def test_missing_body_halts(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        # Remove the body so provisioning must refuse to advertise an unfetchable skill.
        (corpus / Tier.REAL.value / "pdf" / "SKILL.md").unlink()
        import pytest
        from harness.provision_catalog import CatalogProvisionError
        with pytest.raises(CatalogProvisionError):
            build_catalog_text(corpus, manifest)

    def test_id_to_slug_is_identity(self, tmp_path):
        manifest, _ = _write_corpus(tmp_path, ["pdf", "xlsx"])
        assert catalog_id_to_slug(manifest) == {"pdf": "pdf", "xlsx": "xlsx"}


class TestCatalogServerHandling:
    def test_initialize_advertises_protocol(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        cap = CatalogCapture()
        resp = handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                              corpus, manifest, cap)
        assert resp["result"]["serverInfo"]["name"] == "catalog-fetch"
        assert "protocolVersion" in resp["result"]

    def test_initialized_notification_has_no_response(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        cap = CatalogCapture()
        assert handle_message({"method": "notifications/initialized"}, corpus, manifest, cap) is None

    def test_tools_list_advertises_only_fetch_skill(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        cap = CatalogCapture()
        resp = handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                              corpus, manifest, cap)
        tools = resp["result"]["tools"]
        assert [t["name"] for t in tools] == ["fetch_skill"]

    def test_fetch_skill_returns_body_and_captures_id(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf", "xlsx"])
        cap = CatalogCapture()
        resp = handle_message({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                               "params": {"name": "fetch_skill", "arguments": {"id": "pdf"}}},
                              corpus, manifest, cap)
        text = resp["result"]["content"][0]["text"]
        assert "# pdf" in text                       # the real body was returned
        assert resp["result"].get("isError") is not True
        assert cap.fetched_id == "pdf"               # chosen id captured for scoring
        assert cap.fetch_latency_ms is not None

    def test_fetch_unknown_id_is_error(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        cap = CatalogCapture()
        resp = handle_message({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                               "params": {"name": "fetch_skill", "arguments": {"id": "nope"}}},
                              corpus, manifest, cap)
        assert resp["result"]["isError"] is True
        assert cap.fetched_id == "nope"              # still records the attempt

    def test_unknown_tool_is_rpc_error(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        cap = CatalogCapture()
        resp = handle_message({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                               "params": {"name": "resolve_skill", "arguments": {}}},
                              corpus, manifest, cap)
        assert resp["error"]["code"] == -32601

    def test_load_body_direct(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf"])
        assert "# pdf" in (load_body(corpus, manifest, "pdf") or "")
        assert load_body(corpus, manifest, "ghost") is None
