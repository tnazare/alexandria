"""Unit tests for native-condition provisioning (filesystem only, no stack).

Covers Requirement 1.2 (lay corpus into .claude/skills) and 1.4 (halt on count mismatch / missing
file).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from harness.provision_native import NativeProvisionError, provision


def _build_corpus(tmp_path: Path, slugs_by_tier: dict[str, list[str]], *, size: int) -> Path:
    corpus = tmp_path / "corpus"
    skills = []
    for tier, slugs in slugs_by_tier.items():
        for slug in slugs:
            d = corpus / tier / slug
            d.mkdir(parents=True, exist_ok=True)
            body = f"---\nname: {slug}\ndescription: d\n---\n# {slug}\nBody.\n"
            (d / "SKILL.md").write_text(body, encoding="utf-8")
            skills.append({"slug": slug, "tier": tier, "name": slug, "sha256": f"s-{slug}"})
    manifest = {
        "experiment_version": "v1", "library_size": size, "seed": 1,
        "content_hash": "sha256:x", "skills": skills,
    }
    (corpus / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return corpus


def test_installs_all_skills_and_counts(tmp_path):
    corpus = _build_corpus(tmp_path, {"real": ["pdf", "xlsx"], "padding": ["p1"]}, size=3)
    ws = tmp_path / "ws"
    n = provision(corpus, ws)
    assert n == 3
    installed = sorted(p.parent.name for p in (ws / ".claude" / "skills").glob("*/SKILL.md"))
    assert installed == ["p1", "pdf", "xlsx"]
    # byte-identical content
    assert (ws / ".claude/skills/pdf/SKILL.md").read_text() == (corpus / "real/pdf/SKILL.md").read_text()


def test_is_idempotent_and_clears_stale(tmp_path):
    corpus = _build_corpus(tmp_path, {"real": ["pdf"]}, size=1)
    ws = tmp_path / "ws"
    # Pre-seed a stale skill that must be cleared.
    stale = ws / ".claude" / "skills" / "stale" / "SKILL.md"
    stale.parent.mkdir(parents=True, exist_ok=True)
    stale.write_text("stale", encoding="utf-8")

    n = provision(corpus, ws)
    assert n == 1
    installed = sorted(p.parent.name for p in (ws / ".claude" / "skills").glob("*/SKILL.md"))
    assert installed == ["pdf"]  # stale removed


def test_halts_on_count_mismatch(tmp_path):
    # Manifest claims size=5 but only 2 files exist.
    corpus = _build_corpus(tmp_path, {"real": ["pdf", "xlsx"]}, size=5)
    with pytest.raises(NativeProvisionError, match="corpus size is 5"):
        provision(corpus, tmp_path / "ws")


def test_halts_on_missing_file(tmp_path):
    corpus = _build_corpus(tmp_path, {"real": ["pdf"]}, size=2)
    # Add a manifest entry with no backing file.
    manifest_path = corpus / "manifest.json"
    data = json.loads(manifest_path.read_text())
    data["skills"].append({"slug": "ghost", "tier": "padding", "name": "ghost", "sha256": "g"})
    manifest_path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(NativeProvisionError, match="missing corpus file"):
        provision(corpus, tmp_path / "ws")
