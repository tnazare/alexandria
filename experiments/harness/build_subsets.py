"""Build fixed, named semantic subsets from the frozen superset embeddings (task 3).

Produces six permanent, committed subsets — three *flavors* at two sizes — each a deterministic
selection of skill slugs from the 500-skill superset:

  close-semantics-20 / -100   skills that cluster tightly in meaning (hard precision/recall:
                              can the resolver tell near-duplicates apart?)
  random-20 / -100            seeded uniform picks (neutral baseline)
  sparse-semantics-20 / -100  mutually dissimilar skills (a curated, deduplicated catalog;
                              easy disambiguation — best case)

Each is frozen to ``corpus/subsets/<name>/manifest.json`` listing the member slugs (+ provenance
copied from the superset). Everything is seeded and uses only numpy over the committed embeddings
cache, so no embedding model is needed to rebuild and the result is byte-stable across machines.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger("harness.build_subsets")

SUBSETS_DIR = "corpus/subsets"
SIZES = (20, 100)
FLAVORS = ("close-semantics", "random", "sparse-semantics")
DEFAULT_SEED = 1234


def _stable_offset(name: str) -> int:
    """A deterministic small integer offset from a name (stable across runs; hashlib, not hash())."""
    import hashlib
    return int(hashlib.sha256(name.encode("utf-8")).hexdigest(), 16) % 10_000


def _matrix(cache: dict):
    """Return (slugs, matrix) with slugs in sorted order and rows L2-normalized vectors."""
    import numpy as np
    slugs = sorted(cache["vectors"].keys())
    mat = np.array([cache["vectors"][s] for s in slugs], dtype=float)
    return slugs, mat


def select_random(slugs: list[str], size: int, seed: int) -> list[str]:
    """Seeded uniform sample of ``size`` slugs. Deterministic."""
    import random
    rng = random.Random(seed)
    return sorted(rng.sample(slugs, size))


def select_close(slugs: list[str], mat, size: int, seed: int) -> list[str]:
    """A tight semantic cluster: a seeded anchor plus its ``size-1`` nearest neighbors (cosine)."""
    import numpy as np
    rng = __import__("random").Random(seed)
    anchor = rng.randrange(len(slugs))
    sims = mat @ mat[anchor]                      # cosine (vectors are L2-normalized)
    order = np.argsort(-sims)                     # most similar first (anchor itself is included)
    idx = order[:size]
    return sorted(slugs[i] for i in idx)


def select_sparse(slugs: list[str], mat, size: int, seed: int) -> list[str]:
    """Maximally dissimilar skills via greedy farthest-point sampling (max-min cosine)."""
    import numpy as np
    rng = __import__("random").Random(seed)
    start = rng.randrange(len(slugs))
    chosen = [start]
    # min cosine similarity of each point to the chosen set (lower = farther / more dissimilar).
    min_sim = mat @ mat[start]
    while len(chosen) < size:
        # pick the point whose closest chosen neighbor is the LEAST similar (farthest-point).
        min_sim_masked = min_sim.copy()
        for c in chosen:
            min_sim_masked[c] = np.inf             # never re-pick
        nxt = int(np.argmin(min_sim_masked))
        chosen.append(nxt)
        min_sim = np.minimum(min_sim, mat @ mat[nxt])
    return sorted(slugs[i] for i in chosen)


def _mean_pairwise_sim(mat, idxs) -> float:
    """Mean off-diagonal cosine similarity among selected rows (a cohesion diagnostic)."""
    import numpy as np
    sub = mat[idxs]
    sims = sub @ sub.T
    n = len(idxs)
    if n < 2:
        return 0.0
    off = (sims.sum() - np.trace(sims)) / (n * (n - 1))
    return round(float(off), 4)


def build_subset(flavor: str, size: int, cache: dict, superset: dict, seed: int) -> dict:
    """Build one subset manifest dict (does not write to disk)."""
    import numpy as np
    slugs, mat = _matrix(cache)
    if flavor == "random":
        members = select_random(slugs, size, seed)
    elif flavor == "close-semantics":
        members = select_close(slugs, mat, size, seed)
    elif flavor == "sparse-semantics":
        members = select_sparse(slugs, mat, size, seed)
    else:
        raise ValueError(f"unknown flavor: {flavor}")

    by_slug = {s["slug"]: s for s in superset["skills"]}
    idxs = [slugs.index(m) for m in members]
    return {
        "kind": "subset",
        "name": f"{flavor}-{size}",
        "flavor": flavor,
        "size": size,
        "seed": seed,
        "embedding_model": cache["model"],
        "cohesion_mean_cosine": _mean_pairwise_sim(mat, idxs),   # diagnostic: close high, sparse low
        "skills": [
            {"slug": m, "name": by_slug[m]["name"], "sha256": by_slug[m]["sha256"],
             "source_url": by_slug[m].get("source_url"), "ref": by_slug[m].get("ref"),
             "license": by_slug[m].get("license")}
            for m in members
        ],
    }


def write_subset(manifest: dict, out_dir: str | Path = SUBSETS_DIR) -> Path:
    d = Path(out_dir) / manifest["name"]
    d.mkdir(parents=True, exist_ok=True)
    p = d / "manifest.json"
    p.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return p


def load_subset(name: str, out_dir: str | Path = SUBSETS_DIR) -> dict:
    return json.loads((Path(out_dir) / name / "manifest.json").read_text(encoding="utf-8"))


def main() -> int:
    import argparse
    from harness.embeddings import load_embeddings
    from harness.build_superset import load_superset

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Build fixed named semantic subsets (task 3).")
    parser.add_argument("--out", default=SUBSETS_DIR)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args()

    cache = load_embeddings()
    superset = load_superset()
    if len(cache["vectors"]) != superset["size"]:
        raise SystemExit(f"embeddings ({len(cache['vectors'])}) != superset ({superset['size']})")

    for flavor in FLAVORS:
        for size in SIZES:
            # Deterministic per-(flavor,size) seed so flavors don't share an anchor AND the result is
            # byte-stable across runs (Python's hash() is salted, so derive the offset from a fixed
            # stable hash of the name instead).
            seed = args.seed + _stable_offset(f"{flavor}-{size}")
            m = build_subset(flavor, size, cache, superset, seed)
            p = write_subset(m, args.out)
            print(f"{m['name']:22} size={m['size']:3} cohesion(mean cos)={m['cohesion_mean_cosine']:.3f} -> {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
