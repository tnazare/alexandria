"""Deterministic categorization of skills into a small set of clusters (hierarchical disclosure).

The catalog condition keeps *every* skill's line resident, so its resident cost grows linearly in N.
Hierarchical disclosure fixes that: cluster the N skills into ``C`` categories, keep only ``C``
category headers resident, and disclose a category's skill lines only when the agent opens it. Resident
cost becomes O(C) instead of O(N), while selection still begins from resident text (the category
headers) — the property that gave the catalog condition its compliance.

This module derives the categories offline and deterministically from the **committed superset
embeddings** (same cache :mod:`harness.build_subsets` uses), via seeded k-means over L2-normalized
vectors (cosine == dot product). No embedding model or network is needed to recompute, and the result
is byte-stable for a given (slugs, C, seed). A category's label is a short, deterministic keyword
summary drawn from its members' names/descriptions, so headers are human- (and model-) readable.

Pure functions throughout; numpy is imported lazily so the import graph stays light.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from harness.frontmatter import parse_frontmatter

EMBEDDINGS_CACHE = "corpus/embeddings/embeddings.json"

# Common words we never want as a category label (too generic to disambiguate categories).
_STOPWORDS = {
    "the", "a", "an", "and", "or", "for", "to", "of", "in", "on", "with", "using", "use", "used",
    "skill", "skills", "guide", "helper", "tool", "tools", "that", "this", "your", "you", "when",
    "how", "it", "is", "are", "be", "by", "as", "at", "from", "into", "via", "can", "will", "do",
    "create", "creating", "build", "building", "work", "working", "based", "across", "any", "all",
}


@dataclass(frozen=True)
class Category:
    """One derived category: a stable id, a short label, and the member slugs (sorted)."""

    cid: int
    label: str
    slugs: list[str]


@dataclass(frozen=True)
class Categorization:
    """The full assignment of a slug set into categories (deterministic for (slugs, k, seed))."""

    k: int
    seed: int
    categories: list[Category]

    def slug_to_label(self) -> dict[str, str]:
        return {s: c.label for c in self.categories for s in c.slugs}

    def label_to_slugs(self) -> dict[str, list[str]]:
        return {c.label: list(c.slugs) for c in self.categories}


def load_embeddings(cache_path: str | Path = EMBEDDINGS_CACHE) -> dict:
    """Load the committed embeddings cache ({model, dim, vectors{slug: [floats]}})."""
    return json.loads(Path(cache_path).read_text(encoding="utf-8"))


def _submatrix(cache: dict, slugs: list[str]):
    """Return an (len(slugs), dim) numpy matrix of L2-normalized vectors in ``slugs`` order."""
    import numpy as np

    vectors = cache["vectors"]
    missing = [s for s in slugs if s not in vectors]
    if missing:
        raise KeyError(f"{len(missing)} slugs missing from embeddings cache (e.g. {missing[:3]})")
    mat = np.array([vectors[s] for s in slugs], dtype=float)
    # The cache stores L2-normalized vectors; renormalize defensively so dot == cosine.
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return mat / norms


def _kmeans(mat, k: int, seed: int, iters: int = 50):
    """Seeded k-means on L2-normalized rows (cosine). Returns an assignment array of length N.

    Deterministic: seeded k-means++ init, fixed iteration count, stable tie-breaking by lowest index.
    """
    import numpy as np

    n = mat.shape[0]
    k = max(1, min(k, n))
    rng = np.random.RandomState(seed)

    # k-means++ seeding (cosine distance = 1 - sim), deterministic given the seed.
    first = rng.randint(n)
    centers_idx = [first]
    closest_sim = mat @ mat[first]
    for _ in range(1, k):
        dist = 1.0 - closest_sim
        dist = np.clip(dist, 0, None)
        total = dist.sum()
        if total <= 0:
            nxt = int(np.argmin(closest_sim))  # degenerate: all identical — pick deterministically
        else:
            probs = dist / total
            nxt = int(rng.choice(n, p=probs))
        centers_idx.append(nxt)
        closest_sim = np.maximum(closest_sim, mat @ mat[nxt])
    centers = mat[centers_idx].copy()

    assign = np.zeros(n, dtype=int)
    for _ in range(iters):
        sims = mat @ centers.T                      # (n, k) cosine to each center
        new_assign = np.argmax(sims, axis=1)        # nearest center (ties -> lowest index)
        if np.array_equal(new_assign, assign):
            assign = new_assign
            break
        assign = new_assign
        for c in range(k):
            members = mat[assign == c]
            if len(members) == 0:
                continue                            # keep the old center for an empty cluster
            mean = members.mean(axis=0)
            norm = np.linalg.norm(mean)
            centers[c] = mean / norm if norm > 0 else centers[c]
    return assign


_WORD = re.compile(r"[A-Za-z][A-Za-z0-9+]{2,}")


def _label_for(slugs: list[str], corpus_text: dict[str, str]) -> str:
    """Derive a short, deterministic category label from members' name+description text.

    Picks the most frequent salient (non-stopword) terms across the category's skills; ties broken
    alphabetically so the label is stable. Falls back to the lexicographically first slug.
    """
    counts: Counter[str] = Counter()
    for slug in slugs:
        for w in _WORD.findall(corpus_text.get(slug, slug).lower()):
            if w not in _STOPWORDS:
                counts[w] += 1
    if not counts:
        return sorted(slugs)[0] if slugs else "misc"
    # Top terms by (count desc, term asc) for determinism; take up to 2 to form a readable label.
    top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:2]
    return "-".join(w for w, _ in top)


def _corpus_text(corpus_dir: Path, slugs: list[str], tier_value: str = "real") -> dict[str, str]:
    """Map slug -> 'name. description' read from the corpus SKILL.md frontmatter (for labeling)."""
    out: dict[str, str] = {}
    for slug in slugs:
        p = corpus_dir / tier_value / slug / "SKILL.md"
        if not p.is_file():
            out[slug] = slug
            continue
        fm, _ = parse_frontmatter(p.read_text(encoding="utf-8"))
        out[slug] = f"{fm.get('name') or slug}. {fm.get('description') or ''}"
    return out


def categorize(
    slugs: list[str],
    *,
    k: int,
    seed: int = 1234,
    cache: Optional[dict] = None,
    corpus_dir: Optional[Path] = None,
    tier_value: str = "real",
) -> Categorization:
    """Partition ``slugs`` into ``k`` categories via seeded k-means over the committed embeddings.

    ``corpus_dir`` (if given) is used only to derive readable labels from frontmatter; without it the
    label falls back to the member terms present in the slug strings. Deterministic for a fixed
    (sorted slugs, k, seed).
    """
    slugs = sorted(slugs)
    cache = cache or load_embeddings()
    mat = _submatrix(cache, slugs)
    assign = _kmeans(mat, k, seed)

    text = _corpus_text(Path(corpus_dir), slugs, tier_value) if corpus_dir else {s: s for s in slugs}
    cats: list[Category] = []
    used_labels: set[str] = set()
    for cid in sorted(set(int(a) for a in assign)):
        members = sorted(slugs[i] for i in range(len(slugs)) if assign[i] == cid)
        if not members:
            continue
        label = _label_for(members, text)
        # Ensure labels are unique and stable (append the cid if a collision occurs).
        if label in used_labels:
            label = f"{label}-{cid}"
        used_labels.add(label)
        cats.append(Category(cid=cid, label=label, slugs=members))
    cats.sort(key=lambda c: c.label)
    return Categorization(k=len(cats), seed=seed, categories=cats)


def suggest_k(n: int) -> int:
    """A reasonable category count for N skills: ~sqrt(N), clamped to [2, 24].

    Sub-linear in N (that is the whole point — resident headers grow like sqrt(N), not N), while
    keeping each category small enough that disclosing one is cheap.
    """
    import math

    return max(2, min(24, round(math.sqrt(n))))
