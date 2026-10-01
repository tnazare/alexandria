"""Unit tests for subset materialization and content hashing (post-superset rework).

Covers: materializing a named subset from a (fake) superset into a run corpus, the content hash
being deterministic/order-independent, and halting when a superset body is missing.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from harness.build_corpus import CorpusError, compute_content_hash, load_manifest, materialize_subset
from harness.models import ManifestEntry, Tier


def _fake_superset(tmp_path: Path, slugs: list[str]) -> Path:
    """Create a minimal superset dir (bodies + manifest) for testing."""
    sup = tmp_path / "superset"
    skills = []
    for slug in slugs:
        d = sup / slug
        d.mkdir(parents=True, exist_ok=True)
        body = f"---\nname: {slug}\ndescription: desc {slug}\n---\n# {slug}\nBody.\n"
        (d / "SKILL.md").write_text(body, encoding="utf-8")
        skills.append({"slug": slug, "name": slug, "description": f"desc {slug}",
                       "source_url": f"https://src/{slug}", "ref": "r1", "license": "MIT",
                       "sha256": f"sha-{slug}"})
    (sup / "manifest.json").write_text(json.dumps({"kind": "superset", "size": len(slugs),
                                                   "skills": skills}), encoding="utf-8")
    return sup


def _fake_subset(tmp_path: Path, name: str, members: list[str], superset_skills: dict) -> Path:
    subs = tmp_path / "subsets"
    d = subs / name
    d.mkdir(parents=True, exist_ok=True)
    manifest = {
        "kind": "subset", "name": name, "flavor": name.rsplit("-", 1)[0], "size": len(members),
        "seed": 42, "embedding_model": "test",
        "skills": [{"slug": m, "name": superset_skills[m]["name"],
                    "sha256": superset_skills[m]["sha256"],
                    "source_url": superset_skills[m]["source_url"], "ref": "r1", "license": "MIT"}
                   for m in members],
    }
    (d / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return subs


class TestMaterialize:
    def test_materializes_members_from_superset(self, tmp_path):
        sup = _fake_superset(tmp_path, ["pdf", "xlsx", "docx", "api-client"])
        sup_skills = {s["slug"]: s for s in json.loads((sup / "manifest.json").read_text())["skills"]}
        subs = _fake_subset(tmp_path, "random-20", ["pdf", "docx"], sup_skills)

        out = tmp_path / "corpus" / "random-20"
        m = materialize_subset("random-20", out_dir=out, subsets_dir=subs, superset_dir=sup)

        assert m.library_size == 2
        assert all(e.tier == Tier.REAL for e in m.skills)
        assert {e.slug for e in m.skills} == {"pdf", "docx"}
        # bodies copied byte-identically under real/<slug>/SKILL.md
        assert (out / "real" / "pdf" / "SKILL.md").read_text() == (sup / "pdf" / "SKILL.md").read_text()
        # run manifest written and reloadable
        reloaded = load_manifest(out / "manifest.json")
        assert reloaded.content_hash == m.content_hash
        assert reloaded.real_slugs() == {"pdf", "docx"}

    def test_halts_when_subset_missing(self, tmp_path):
        sup = _fake_superset(tmp_path, ["pdf"])
        with pytest.raises(CorpusError, match="not found"):
            materialize_subset("nope-20", out_dir=tmp_path / "c",
                               subsets_dir=tmp_path / "subsets", superset_dir=sup)

    def test_halts_when_superset_body_missing(self, tmp_path):
        sup = _fake_superset(tmp_path, ["pdf"])
        sup_skills = {s["slug"]: s for s in json.loads((sup / "manifest.json").read_text())["skills"]}
        # subset references a slug whose body does not exist in the superset
        sup_skills["ghost"] = {"name": "ghost", "sha256": "g", "source_url": "u"}
        subs = _fake_subset(tmp_path, "random-20", ["pdf", "ghost"], sup_skills)
        with pytest.raises(CorpusError, match="superset body missing"):
            materialize_subset("random-20", out_dir=tmp_path / "c",
                               subsets_dir=subs, superset_dir=sup)


class TestContentHash:
    def test_stable_and_order_independent(self):
        e1 = [ManifestEntry(slug="a", tier=Tier.REAL, name="A", sha256="x"),
              ManifestEntry(slug="b", tier=Tier.REAL, name="B", sha256="y")]
        e2 = list(reversed(e1))
        assert compute_content_hash(e1) == compute_content_hash(e2)
        assert compute_content_hash(e1).startswith("sha256:")

    def test_different_members_change_hash(self):
        e1 = [ManifestEntry(slug="a", tier=Tier.REAL, name="A", sha256="x")]
        e2 = [ManifestEntry(slug="a", tier=Tier.REAL, name="A", sha256="z")]
        assert compute_content_hash(e1) != compute_content_hash(e2)
