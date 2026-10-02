"""Token-scaling curve: resident + per-task context cost vs library size N (no LLM, no API spend).

This answers the one claim the full 500-skill LLM matrix was meant to test — **does native's
in-context cost grow linearly in N while Alexandria's stays flat?** — using only the deterministic
token model (``harness.measure_tokens``) over the frozen 500-skill superset. It is a pure Python
computation: no Alexandria stack, no ``claude`` CLI, no API usage.

Why this is a valid stand-in for the expensive run: ``context_token_cost`` is *modeled*, not scraped
from Claude (see PROTOCOL "What is measured vs. modeled"). ``native_resident_tokens`` sums every
skill's frontmatter name+description; ``alexandria_resident_tokens`` is two fixed tool schemas.
Neither needs the model. So the token axis (H1) is fully computable offline at any N.

For each N we report, per condition, the context tokens that *service one task*:

- **resident** — always in context regardless of the task.
  - native: Σ over all N skills of tokens(name + description)  → grows ~linearly in N.
  - alexandria: tokens(resolve_skill schema) + tokens(fetch_skill schema)  → constant in N.
- **per_task** — brought in to answer one task (a representative skill body is disclosed/fetched).
  - native: full body of the matched skill (progressive disclosure).
  - alexandria: resolve result payload + fetched body.
  Modeled with the *median* body size over the sampled corpus so both conditions pay the same
  disclosure, isolating the resident-cost difference that is the whole point of the curve.
- **total** = resident + per_task.

The **crossover N** — the smallest sampled N at which native total first exceeds alexandria total —
falls straight out and is the architectural number: below it native is cheaper (its flat per-skill
preload is tiny), above it Alexandria's constant resident cost wins.

Usage (from experiments/):

    .venv/bin/python -m harness.token_scaling \
        --superset corpus/superset --sizes 20,50,100,200,500 --seed 1234 \
        --out results/token-scaling

Writes ``<out>/token_scaling.csv`` and ``<out>/token_scaling.json`` and prints a table + crossover.
Deterministic: same superset + sizes + seed → byte-identical output.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

from harness.categorize import categorize, suggest_k
from harness.frontmatter import parse_frontmatter
from harness.measure_tokens import (
    FETCH_BY_ID_SCHEMA,
    RESOLVE_SKILL_SCHEMA,
    alexandria_resident_tokens,
    catalog_line,
    category_header_line,
    hierarchical_resident_tokens,
)
from harness.tokenizer import Tokenizer


@dataclass(frozen=True)
class SupersetSkill:
    """One superset member with its frontmatter name/description and full body text."""

    slug: str
    name: str
    description: str
    body: str


def load_superset(superset_dir: Path) -> list[SupersetSkill]:
    """Load every superset skill's name/description (from frontmatter) and full body text.

    The manifest supplies slug ordering; name/description are re-parsed from each SKILL.md so the
    counted text is exactly what each condition would place in context (identical to the live
    provisioners, which install the same files).
    """
    manifest = json.loads((superset_dir / "manifest.json").read_text(encoding="utf-8"))
    skills: list[SupersetSkill] = []
    for entry in manifest["skills"]:
        slug = entry["slug"]
        body = (superset_dir / slug / "SKILL.md").read_text(encoding="utf-8")
        fm, _ = parse_frontmatter(body)
        skills.append(SupersetSkill(
            slug=slug,
            name=str(fm.get("name") or entry.get("name") or ""),
            description=str(fm.get("description") or entry.get("description") or ""),
            body=body,
        ))
    return skills


@dataclass(frozen=True)
class ScalingPoint:
    """Computed context-token cost at one library size N, all three conditions."""

    n: int
    native_resident: int
    native_per_task: int        # median body (progressive disclosure of the matched skill)
    native_total: int
    alexandria_resident: int
    alexandria_per_task: int    # resolve result payload + median fetched body
    alexandria_total: int
    catalog_resident: int       # one fetch schema + Σ compact catalog lines (grows in N, like native)
    catalog_per_task: int       # median fetched body (one fetch)
    catalog_total: int
    hierarchical_resident: int  # two schemas + Σ category HEADERS only (O(C) ~ sqrt(N))
    hierarchical_per_task: int  # one opened category's lines + median fetched body
    hierarchical_total: int
    categories: int             # C used at this N (diagnostic)

    @property
    def ratio_native_over_alx(self) -> float:
        return self.native_total / self.alexandria_total if self.alexandria_total else float("inf")

    @property
    def ratio_native_over_catalog(self) -> float:
        return self.native_total / self.catalog_total if self.catalog_total else float("inf")

    @property
    def ratio_catalog_over_hier_resident(self) -> float:
        """How much the hierarchical scheme shrinks *resident* cost vs the flat catalog."""
        return (self.catalog_resident / self.hierarchical_resident
                if self.hierarchical_resident else float("inf"))


def _sample(skills: list[SupersetSkill], n: int, seed: int) -> list[SupersetSkill]:
    """Deterministic size-N sample of the superset (seeded shuffle, then take N)."""
    if n > len(skills):
        raise ValueError(f"size {n} exceeds superset of {len(skills)}")
    rng = random.Random(seed)
    pool = list(skills)
    rng.shuffle(pool)
    return pool[:n]


def _resolve_result_text(candidates: list[SupersetSkill]) -> str:
    """Model the resolve_skill payload: a short ranked list of candidate name+description lines.

    This is the only part Alexandria puts in context before the fetch; it is small and ~constant in N
    (the ranked list is capped), not a function of the whole library.
    """
    top = candidates[:5]
    lines = [f"- {c.slug}: {c.name} — {c.description}" for c in top]
    return "candidates:\n" + "\n".join(lines)


def compute_point(skills: list[SupersetSkill], n: int, seed: int, count,
                  embeddings_cache: Optional[dict] = None) -> ScalingPoint:
    """Compute the token model at size N using a representative (median-body) disclosure."""
    sample = _sample(skills, n, seed)

    # Native resident: Σ frontmatter name + description over all N skills (the startup preload).
    native_resident = sum(count(s.name) + count(s.description) for s in sample)

    # Alexandria resident: the two fixed tool schemas — constant in N.
    alx_resident = alexandria_resident_tokens(count)

    # Per-task disclosure: use the MEDIAN body in the sample so both conditions pay the identical
    # body cost. This isolates the resident difference (the thing that scales), rather than letting a
    # lucky/unlucky matched skill move the comparison.
    body_tokens = sorted(count(s.body) for s in sample)
    median_body = int(statistics.median(body_tokens)) if body_tokens else 0

    native_per_task = median_body
    resolve_text = _resolve_result_text(sample)
    alx_per_task = count(resolve_text) + median_body

    # Catalog resident: one fetch schema + Σ compact "id: name — description" lines over all N.
    # Grows with N like native, but each line is leaner than native's full preload framing, and it
    # carries a single fetch tool instead of a flat body index.
    catalog_resident = count(FETCH_BY_ID_SCHEMA) + sum(
        count(catalog_line(s.slug, s.name, s.description)) for s in sample
    )
    catalog_per_task = median_body  # one fetched body, same as native's disclosure

    # Hierarchical: cluster the sample into C ~ sqrt(N) categories (deterministic k-means over the
    # committed embeddings). Only the C category HEADERS stay resident; one opened category's lines
    # + one body are the per-task cost. by_slug lets us look up a member's catalog line + name/desc.
    by_slug = {s.slug: s for s in sample}
    k = suggest_k(n)
    cat = categorize([s.slug for s in sample], k=k, seed=seed, cache=embeddings_cache)
    # Resident headers: "label (count): <summary>" — summary is the label-term string, cheap & stable.
    headers = [(c.label, len(c.slugs), c.label.replace("-", " ")) for c in cat.categories]
    hier_resident = hierarchical_resident_tokens(count, headers)
    # Per-task: the agent opens ONE category. Model the *median-sized* category's disclosed lines so
    # the per-task cost is representative rather than best/worst case. Pick by position in the
    # size-sorted category list (robust to even counts / repeated sizes).
    cats_by_size = sorted(cat.categories, key=lambda c: (len(c.slugs), c.label))
    median_cat = cats_by_size[len(cats_by_size) // 2] if cats_by_size else None
    opened_lines = []
    if median_cat:
        for slug in median_cat.slugs:
            s = by_slug[slug]
            opened_lines.append(catalog_line(s.slug, s.name, s.description))
    hier_per_task = count("\n".join(opened_lines)) + median_body

    return ScalingPoint(
        n=n,
        native_resident=native_resident,
        native_per_task=native_per_task,
        native_total=native_resident + native_per_task,
        alexandria_resident=alx_resident,
        alexandria_per_task=alx_per_task,
        alexandria_total=alx_resident + alx_per_task,
        catalog_resident=catalog_resident,
        catalog_per_task=catalog_per_task,
        catalog_total=catalog_resident + catalog_per_task,
        hierarchical_resident=hier_resident,
        hierarchical_per_task=hier_per_task,
        hierarchical_total=hier_resident + hier_per_task,
        categories=len(cat.categories),
    )


def crossover_n(points: list[ScalingPoint]) -> Optional[int]:
    """Smallest N where native_total first exceeds alexandria_total, or None if never within range."""
    for p in sorted(points, key=lambda x: x.n):
        if p.native_total > p.alexandria_total:
            return p.n
    return None


def run(superset_dir: Path, sizes: list[int], seed: int, encoding: str) -> dict:
    from harness.categorize import load_embeddings

    tok = Tokenizer(encoding)
    skills = load_superset(superset_dir)
    cache = load_embeddings()
    points = [compute_point(skills, n, seed, tok.count, embeddings_cache=cache)
              for n in sorted(sizes)]
    return {
        "encoding": encoding,
        "seed": seed,
        "superset_size": len(skills),
        "resolve_schema_tokens": tok.count(RESOLVE_SKILL_SCHEMA),
        "crossover_n": crossover_n(points),
        "points": [asdict(p) | {
            "ratio_native_over_alx": round(p.ratio_native_over_alx, 3),
            "ratio_native_over_catalog": round(p.ratio_native_over_catalog, 3),
            "ratio_catalog_over_hier_resident": round(p.ratio_catalog_over_hier_resident, 3),
        } for p in points],
    }


def _write_csv(result: dict, path: Path) -> None:
    header = ("n,categories,native_resident,native_total,"
              "alexandria_resident,alexandria_total,"
              "catalog_resident,catalog_total,"
              "hierarchical_resident,hierarchical_per_task,hierarchical_total,"
              "ratio_native_over_alx,ratio_native_over_catalog,ratio_catalog_over_hier_resident")
    rows = [header]
    for p in result["points"]:
        rows.append(
            f"{p['n']},{p['categories']},{p['native_resident']},{p['native_total']},"
            f"{p['alexandria_resident']},{p['alexandria_total']},"
            f"{p['catalog_resident']},{p['catalog_total']},"
            f"{p['hierarchical_resident']},{p['hierarchical_per_task']},{p['hierarchical_total']},"
            f"{p['ratio_native_over_alx']},{p['ratio_native_over_catalog']},"
            f"{p['ratio_catalog_over_hier_resident']}"
        )
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def _print_table(result: dict) -> None:
    print(f"\nToken-scaling curve (encoding={result['encoding']}, seed={result['seed']}, "
          f"superset={result['superset_size']})")
    print("Context tokens to service one task — resident (always in ctx) + per-task (one body).")
    print("hier = hierarchical disclosure (C category headers resident, one category opened per task).\n")
    print(f"{'N':>5} {'C':>3} | {'nat_res':>8} {'cat_res':>8} {'hier_res':>8} | "
          f"{'nat_tot':>8} {'cat_tot':>8} {'hier_tot':>8} | {'cat_res/hier':>12}")
    print("-" * 86)
    for p in result["points"]:
        print(f"{p['n']:>5} {p['categories']:>3} | "
              f"{p['native_resident']:>8} {p['catalog_resident']:>8} {p['hierarchical_resident']:>8} | "
              f"{p['native_total']:>8} {p['catalog_total']:>8} {p['hierarchical_total']:>8} | "
              f"{p['ratio_catalog_over_hier_resident']:>12}")
    xo = result["crossover_n"]
    print()
    if xo is None:
        print(f"Crossover: native never exceeds Alexandria within sampled sizes "
              f"(max N={result['points'][-1]['n']}). Native stays cheaper across this range.")
    else:
        print(f"Crossover N = {xo}: at N>={xo}, native's linear preload exceeds Alexandria's "
              f"flat resident cost. Below it, native is cheaper.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--superset", default="corpus/superset", help="superset dir with manifest.json")
    parser.add_argument("--sizes", default="20,50,100,200,500",
                        help="comma-separated library sizes to compute")
    parser.add_argument("--seed", type=int, default=1234, help="sample seed (matches corpus seed)")
    parser.add_argument("--encoding", default="cl100k_base", help="tiktoken encoding")
    parser.add_argument("--out", default="results/token-scaling", help="output directory")
    args = parser.parse_args()

    sizes = [int(s) for s in args.sizes.split(",") if s.strip()]
    result = run(Path(args.superset), sizes, args.seed, args.encoding)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "token_scaling.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    _write_csv(result, out / "token_scaling.csv")

    _print_table(result)
    print(f"\nWrote {out / 'token_scaling.json'} and {out / 'token_scaling.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
