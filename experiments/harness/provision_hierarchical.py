"""Provision the hierarchical-disclosure condition: resident Category Index text + maps.

The hierarchical condition's "provisioning" produces the resident **Category Index** block appended
to Claude's system prompt — one ``label (count): summary`` header per derived category — plus the
``label -> slugs`` map the local server (:mod:`harness.hierarchical_server`) uses to answer
``list_category``. Categories are derived offline and deterministically by
:func:`harness.categorize.categorize` (seeded k-means over the committed embeddings), so the whole
thing is reproducible and needs no backend.

Only the headers are resident; the per-skill lines live behind ``list_category``. This is pure text
generation plus the categorization lookup, so it is fully unit-testable offline.
"""

from __future__ import annotations

from pathlib import Path

from harness.build_corpus import load_manifest
from harness.categorize import Categorization, categorize, suggest_k
from harness.measure_tokens import category_header_line
from harness.models import CorpusManifest


class HierarchicalProvisionError(RuntimeError):
    pass


def build_categorization(
    corpus_dir: str | Path,
    manifest: CorpusManifest | None = None,
    *,
    k: int | None = None,
    seed: int = 1234,
    tier_value: str = "real",
) -> Categorization:
    """Derive the categorization for a corpus subset (deterministic for (slugs, k, seed))."""
    corpus = Path(corpus_dir)
    manifest = manifest or load_manifest(corpus / "manifest.json")
    slugs = [e.slug for e in manifest.skills]
    k = k or suggest_k(len(slugs))
    return categorize(slugs, k=k, seed=seed, corpus_dir=corpus, tier_value=tier_value)


def build_category_index_text(cat: Categorization) -> str:
    """Return the resident Category Index block: one ``label (count): summary`` header per category.

    The summary is a readable rendering of the label terms (deterministic, cheap). Order follows the
    categorization (already sorted by label), so the block is stable.
    """
    lines = [category_header_line(c.label, len(c.slugs), c.label.replace("-", " "))
             for c in cat.categories]
    return "\n".join(lines)


def hierarchical_system_prompt(directive: str, category_index_text: str) -> str:
    """Compose the appended system prompt: the consult directive + the resident Category Index."""
    directive = " ".join(directive.split()) if directive else ""
    return (f"{directive}\n\n## Category Index (label (count): summary)\n{category_index_text}").strip()


def label_to_slugs(cat: Categorization) -> dict[str, list[str]]:
    """``label -> member slugs`` map the server uses to answer list_category."""
    return cat.label_to_slugs()


def hierarchical_id_to_slug(cat: Categorization) -> dict[str, str]:
    """Identity map slug->slug: fetched ids are slugs, so scoring maps directly."""
    return {s: s for c in cat.categories for s in c.slugs}


def category_headers_for_tokens(cat: Categorization) -> list[tuple[str, int, str]]:
    """``(label, count, summary)`` tuples for the hierarchical token model's resident cost."""
    return [(c.label, len(c.slugs), c.label.replace("-", " ")) for c in cat.categories]
