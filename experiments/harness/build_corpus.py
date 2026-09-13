"""Corpus builder: assemble a size-N corpus from the three tiers (Requirements 1.1, 1.3, 9.1, 9.6).

A size-N corpus = **all** Real_Tier + **all** Distractor_Tier + enough deterministic Padding_Tier
to reach N. The same assembled corpus drives both conditions, so content is byte-identical and only
the *mechanism* differs (Requirement 1.1). A ``content_hash`` over the sorted ``(slug, sha256)``
pairs pins the corpus into every RunRecord (Requirement 1.3).

If N is smaller than the real+distractor count, that is an error (we never drop real/distractor
skills to hit a size); if a skill fails to materialize, the build halts (Requirement 1.4).
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Optional

from harness.generate_padding import generate as generate_padding
from harness.harvest import HarvestedSkill
from harness.models import CorpusManifest, GeneratedSkill, ManifestEntry, Tier


class CorpusError(RuntimeError):
    """Raised when a corpus cannot be assembled at the requested size."""


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


def assemble(
    real: list[HarvestedSkill],
    distractors: list[HarvestedSkill],
    *,
    library_size: int,
    seed: int,
    experiment_version: str = "v1",
) -> tuple[CorpusManifest, list[GeneratedSkill]]:
    """Assemble a size-N manifest and the padding skills needed to reach it.

    Returns ``(manifest, padding_skills)``. Pure and network-free (padding is generated here), so it
    is unit-testable. Writing files to disk is done separately by :func:`write` .
    """
    base_count = len(real) + len(distractors)
    if library_size < base_count:
        raise CorpusError(
            f"library_size {library_size} is smaller than the {base_count} real+distractor skills; "
            f"real/distractor skills are never dropped to hit a size (Requirement 9.4)."
        )

    reserved = {s.slug for s in real} | {s.slug for s in distractors}
    n_padding = library_size - base_count
    padding = generate_padding(n_padding, seed=seed, reserved_slugs=reserved) if n_padding else []

    entries: list[ManifestEntry] = []
    for s in real:
        entries.append(ManifestEntry(slug=s.slug, tier=Tier.REAL, name=s.name, sha256=s.sha256,
                                     source_url=s.source_url, ref=s.ref, license=s.license))
    for s in distractors:
        entries.append(ManifestEntry(slug=s.slug, tier=Tier.DISTRACTOR, name=s.name, sha256=s.sha256,
                                     source_url=s.source_url, ref=s.ref, license=s.license))
    for g in padding:
        entries.append(ManifestEntry(slug=g.slug, tier=Tier.PADDING, name=g.name, sha256=g.sha256))

    # Guard against any slug collision across tiers (would make provisioning ambiguous).
    slugs = [e.slug for e in entries]
    if len(slugs) != len(set(slugs)):
        dupes = sorted({s for s in slugs if slugs.count(s) > 1})
        raise CorpusError(f"slug collision across tiers: {dupes}")

    if len(entries) != library_size:
        raise CorpusError(f"assembled {len(entries)} skills, expected {library_size}")

    manifest = CorpusManifest(
        experiment_version=experiment_version,
        library_size=library_size,
        seed=seed,
        content_hash=compute_content_hash(entries),
        skills=entries,
    )
    return manifest, padding


def write(
    manifest: CorpusManifest,
    real: list[HarvestedSkill],
    distractors: list[HarvestedSkill],
    padding: list[GeneratedSkill],
    corpus_dir: str | Path,
) -> None:
    """Write all skill files under ``<corpus_dir>/<tier>/<slug>/SKILL.md`` and the manifest JSON.

    Halts (raises) if any skill body is missing (Requirement 1.4).
    """
    base = Path(corpus_dir)
    bodies: dict[str, tuple[str, str]] = {}  # slug -> (tier, body)
    for s in real:
        bodies[s.slug] = (Tier.REAL.value, s.body)
    for s in distractors:
        bodies[s.slug] = (Tier.DISTRACTOR.value, s.body)
    for g in padding:
        bodies[g.slug] = (Tier.PADDING.value, g.body)

    for entry in manifest.skills:
        if entry.slug not in bodies:
            raise CorpusError(f"no body for skill '{entry.slug}'; refusing to write partial corpus")
        tier, body = bodies[entry.slug]
        dest = base / tier / entry.slug / "SKILL.md"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(body.rstrip("\n") + "\n", encoding="utf-8")

    base.mkdir(parents=True, exist_ok=True)
    Path(base, "manifest.json").write_text(_manifest_json(manifest), encoding="utf-8")


def _manifest_json(manifest: CorpusManifest) -> str:
    return json.dumps(
        {
            "experiment_version": manifest.experiment_version,
            "library_size": manifest.library_size,
            "seed": manifest.seed,
            "content_hash": manifest.content_hash,
            "skills": [
                {
                    "slug": e.slug, "tier": e.tier.value, "name": e.name, "sha256": e.sha256,
                    "source_url": e.source_url, "ref": e.ref, "license": e.license,
                }
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

    from harness.harvest import harvest, load_sources

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Build a size-N benchmark corpus.")
    parser.add_argument("--sources", default="config/sources.yaml")
    parser.add_argument("--corpus", default="corpus")
    parser.add_argument("--size", type=int, required=True)
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--experiment-version", default="v1")
    args = parser.parse_args()

    skills = harvest(load_sources(args.sources))
    real = [s for s in skills if s.tier == Tier.REAL]
    distractors = [s for s in skills if s.tier == Tier.DISTRACTOR]
    manifest, padding = assemble(
        real, distractors, library_size=args.size, seed=args.seed,
        experiment_version=args.experiment_version,
    )
    write(manifest, real, distractors, padding, args.corpus)
    print(f"Built corpus N={manifest.library_size} "
          f"(real={len(real)}, distractor={len(distractors)}, padding={len(padding)}) "
          f"hash={manifest.content_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
