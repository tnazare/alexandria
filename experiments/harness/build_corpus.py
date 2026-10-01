"""Corpus builder: materialize a named subset from the frozen superset (task 5).

A benchmark run operates on one **named subset** (e.g. ``close-semantics-20``) — a fixed, committed
selection of real skills from the 500-skill superset. This module copies that subset's SKILL.md
bodies out of ``corpus/superset/`` into a run directory ``corpus/<subset-name>/<slug>/SKILL.md`` and
writes a ``manifest.json`` (same :class:`CorpusManifest` shape the provisioners and metrics consume),
with a ``content_hash`` over the sorted ``(slug, sha256)`` pairs that pins the corpus into every
RunRecord.

All skills are real (``Tier.REAL``) — the padding/distractor tiers are gone now that the corpus comes
from a curated real superset. Materialization is a pure filesystem copy, deterministic and offline.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from harness.models import CorpusManifest, ManifestEntry, Tier

SUPERSET_DIR = "corpus/superset"
SUBSETS_DIR = "corpus/subsets"


class CorpusError(RuntimeError):
    """Raised when a corpus cannot be materialized (missing superset body, bad subset, etc.)."""


def compute_content_hash(entries: list[ManifestEntry]) -> str:
    """Deterministic hash over sorted ``(slug, sha256)`` pairs (order-independent)."""
    pairs = sorted((e.slug, e.sha256) for e in entries)
    h = hashlib.sha256()
    for slug, sha in pairs:
        h.update(slug.encode("utf-8"))
        h.update(b"\x00")
        h.update(sha.encode("utf-8"))
        h.update(b"\n")
    return "sha256:" + h.hexdigest()


def materialize_subset(
    subset_name: str,
    *,
    out_dir: str | Path,
    subsets_dir: str | Path = SUBSETS_DIR,
    superset_dir: str | Path = SUPERSET_DIR,
    experiment_version: str = "v1",
) -> CorpusManifest:
    """Copy a named subset's skills from the superset into ``out_dir`` and write its run manifest.

    Returns the :class:`CorpusManifest`. Halts (raises) if the subset manifest is missing or any
    member's SKILL.md body is absent from the superset.
    """
    subset_manifest_path = Path(subsets_dir) / subset_name / "manifest.json"
    if not subset_manifest_path.is_file():
        raise CorpusError(f"subset '{subset_name}' not found at {subset_manifest_path}")
    subset = json.loads(subset_manifest_path.read_text(encoding="utf-8"))

    base = Path(out_dir)
    base.mkdir(parents=True, exist_ok=True)
    entries: list[ManifestEntry] = []
    for s in subset["skills"]:
        slug = s["slug"]
        src = Path(superset_dir) / slug / "SKILL.md"
        if not src.is_file():
            raise CorpusError(f"superset body missing for '{slug}': {src}")
        dest = base / Tier.REAL.value / slug / "SKILL.md"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
        entries.append(ManifestEntry(
            slug=slug, tier=Tier.REAL, name=s["name"], sha256=s["sha256"],
            source_url=s.get("source_url"), ref=s.get("ref"), license=s.get("license"),
        ))

    manifest = CorpusManifest(
        experiment_version=experiment_version,
        library_size=len(entries),
        seed=int(subset.get("seed", 0)),
        content_hash=compute_content_hash(entries),
        skills=entries,
    )
    Path(base, "manifest.json").write_text(_manifest_json(manifest, subset_name), encoding="utf-8")
    return manifest


def _manifest_json(manifest: CorpusManifest, subset_name: str) -> str:
    return json.dumps(
        {
            "experiment_version": manifest.experiment_version,
            "subset": subset_name,
            "library_size": manifest.library_size,
            "seed": manifest.seed,
            "content_hash": manifest.content_hash,
            "skills": [
                {"slug": e.slug, "tier": e.tier.value, "name": e.name, "sha256": e.sha256,
                 "source_url": e.source_url, "ref": e.ref, "license": e.license}
                for e in manifest.skills
            ],
        },
        indent=2,
    )


def load_manifest(path: str | Path) -> CorpusManifest:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    entries = [
        ManifestEntry(
            slug=e["slug"], tier=Tier(e["tier"]), name=e["name"], sha256=e["sha256"],
            source_url=e.get("source_url"), ref=e.get("ref"), license=e.get("license"),
        )
        for e in data["skills"]
    ]
    return CorpusManifest(
        experiment_version=data["experiment_version"],
        library_size=data["library_size"],
        seed=data["seed"],
        content_hash=data["content_hash"],
        skills=entries,
    )


def main() -> int:
    import argparse
    import logging

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Materialize a named subset corpus from the superset.")
    parser.add_argument("--subset", required=True, help="subset name, e.g. close-semantics-20")
    parser.add_argument("--out", default=None, help="output dir (default: corpus/<subset>)")
    parser.add_argument("--experiment-version", default="v1")
    args = parser.parse_args()

    out = args.out or f"corpus/{args.subset}"
    m = materialize_subset(args.subset, out_dir=out, experiment_version=args.experiment_version)
    print(f"Materialized subset '{args.subset}': {m.library_size} skills -> {out} "
          f"hash={m.content_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
