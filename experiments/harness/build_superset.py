"""Build and freeze the permanent 500-skill real superset (task 1).

Harvests a deterministic ``size``-skill real-only superset from the pinned sources in
``config/sources.yaml`` and writes it as **permanent, committed data** under ``corpus/superset/``:

    corpus/superset/<slug>/SKILL.md     one directory per skill (byte-identical to source)
    corpus/superset/manifest.json       frozen manifest: slug, name, source_url, ref, license, sha256

The superset is the single source of truth; the named subsets (close/random/sparse at 20/100) are
deterministic selections FROM it (see build_subsets.py). It is built once and committed, so runs
never depend on re-harvesting the internet.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from harness.harvest import harvest_superset, load_sources, load_superset_config
from harness.models import HarvestedSkill

log = logging.getLogger("harness.build_superset")

SUPERSET_DIR = "corpus/superset"


def write_superset(skills: list[HarvestedSkill], out_dir: str | Path) -> Path:
    """Write skill bodies + a frozen manifest. Returns the manifest path."""
    base = Path(out_dir)
    base.mkdir(parents=True, exist_ok=True)
    for s in skills:
        dest = base / s.slug / "SKILL.md"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(s.body.rstrip("\n") + "\n", encoding="utf-8")

    manifest = {
        "kind": "superset",
        "size": len(skills),
        "skills": [
            {"slug": s.slug, "name": s.name, "description": s.description,
             "source_url": s.source_url, "ref": s.ref, "license": s.license, "sha256": s.sha256}
            for s in sorted(skills, key=lambda x: x.slug)
        ],
    }
    manifest_path = base / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest_path


def load_superset(out_dir: str | Path = SUPERSET_DIR) -> dict:
    """Load the frozen superset manifest (the permanent pool the subsets draw from)."""
    return json.loads((Path(out_dir) / "manifest.json").read_text(encoding="utf-8"))


def main() -> int:
    import argparse

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Build and freeze the permanent real-skill superset.")
    parser.add_argument("--sources", default="config/sources.yaml")
    parser.add_argument("--out", default=SUPERSET_DIR)
    parser.add_argument("--size", type=int, default=None, help="override superset size")
    parser.add_argument("--seed", type=int, default=None, help="override selection seed")
    args = parser.parse_args()

    cfg = load_superset_config(args.sources)
    size = args.size or cfg["size"]
    seed = args.seed or cfg["seed"]
    sources = load_sources(args.sources)

    log.info("harvesting superset: target size=%d seed=%d from %d source(s)", size, seed, len(sources))
    skills = harvest_superset(sources, size=size, seed=seed)
    manifest_path = write_superset(skills, args.out)
    print(f"Froze superset: {len(skills)} skills -> {manifest_path}")
    if len(skills) < size:
        print(f"WARNING: only {len(skills)} skills available (< requested {size}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
