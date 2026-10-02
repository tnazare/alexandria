"""Offline Context_Token_Cost measurement (Requirements 3.1, 3.2, 3.3, 3.5).

Computes, with one fixed tokenizer applied to both conditions, the number of tokens each mechanism
introduces into the model context to service a task. Excludes the task prompt and model output that
are common to both conditions.

- **Alexandria** per task = tokens(resolve_skill schema) + tokens(fetch_skill schema)
  + tokens(resolve result payload) + tokens(fetched skill body). The schema part is *resident* and
  constant in N; the rest is per-task.
- **Native** per task = Σ over all N skills of tokens(frontmatter name + description)  [resident,
  grows with N] + tokens(full body of the matched skill)  [progressive disclosure].

All functions are pure: they take a token-counting callable (``count`` : str -> int), so tests can
inject a trivial counter and no ``tiktoken`` runtime is required. In production the callable is
``Tokenizer(encoding).count``.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from harness.frontmatter import parse_frontmatter
from harness.models import CorpusManifest, Tier

Counter = Callable[[str], int]


# --- Alexandria MCP tool schemas (the resident cost, constant in N) --------------------------
# These mirror the two tools advertised by the Alexandria MCP endpoint. They are transcribed here
# (not fetched) so token cost is reproducible offline; the exact wording is disclosed in results.

RESOLVE_SKILL_SCHEMA = (
    "resolve_skill: Find the most relevant governed skill candidates for a task. "
    "Use this first to check whether a reusable workflow already exists. Returns a short ranked "
    "list of candidates with identity, governance (lifecycle/risk/visibility), and match reasons, "
    "but NOT full instructions. After choosing a candidate, call fetch_skill with its "
    "skillVersionId to get the usable instructions. Results respect the caller's access control. "
    'inputSchema: {"type":"object","properties":{"query":{"type":"string"},'
    '"limit":{"type":"integer"}},"required":["query"]}'
)

FETCH_SKILL_SCHEMA = (
    "fetch_skill: Fetch the full, usable instructions for one skill version chosen from "
    "resolve_skill results. Returns the skill as an Agent Skills SKILL.md document by default (or "
    "structured JSON with format=alexandria_native). Denied for blocked skills; deprecated skills "
    "carry a warning. "
    'inputSchema: {"type":"object","properties":{"skillVersionId":{"type":"string"},'
    '"tool":{"type":"string"},"format":{"type":"string"}},"required":["skillVersionId"]}'
)


@dataclass(frozen=True)
class TokenBreakdown:
    """Per-task token cost decomposed into resident vs per-task parts."""

    resident: int          # always in context to service the task (schemas / all frontmatter)
    per_task: int          # brought in for this task (resolve result + fetched body / matched body)

    @property
    def total(self) -> int:
        return self.resident + self.per_task


# --- catalog condition schema (one fetch tool; constant in N) --------------------------------
# The catalog condition injects a compact resident catalog (id: name — description per skill) into
# the system prompt, like native's preload, then fetches ONE body on demand via a single local tool.
# It has no resolve_skill step (selection happens over the resident catalog, as in native), so its
# resident tool cost is just this one schema.

FETCH_BY_ID_SCHEMA = (
    "fetch_skill: Fetch the full instructions for one skill chosen from the resident Skill Catalog. "
    "Pass the catalog id of the skill you selected. Returns the skill's SKILL.md body. "
    'inputSchema: {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}'
)


def catalog_line(skill_id: str, name: str, description: str) -> str:
    """One resident catalog entry: ``id: name — description`` (the model selects an id from these)."""
    return f"{skill_id}: {name} — {description}"


def alexandria_resident_tokens(count: Counter) -> int:
    """Resident cost for Alexandria: the two tool schemas. Independent of N (Requirement 3.2)."""
    return count(RESOLVE_SKILL_SCHEMA) + count(FETCH_SKILL_SCHEMA)


def alexandria_task_tokens(
    count: Counter,
    *,
    resolve_result_text: str,
    fetched_body: str,
) -> TokenBreakdown:
    """Alexandria per-task cost: resident schemas + resolve result payload + fetched body."""
    resident = alexandria_resident_tokens(count)
    per_task = count(resolve_result_text) + count(fetched_body)
    return TokenBreakdown(resident=resident, per_task=per_task)


def catalog_resident_tokens(count: Counter, manifest: CorpusManifest, corpus_dir: Path) -> int:
    """Catalog resident cost: the one fetch schema + Σ compact catalog lines over all N skills.

    Like native, every skill's name+description is resident so the model can select without a
    retrieval round-trip (high consult compliance). Unlike native, the per-skill line is a compact
    ``id: name — description`` lookup row rather than Claude's full preload framing, and there is a
    single fetch tool instead of a flat body preload. Grows with N (the catalog), but carries only
    one schema and no body until a fetch.
    """
    total = count(FETCH_BY_ID_SCHEMA)
    for entry in manifest.skills:
        skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
        fm, _ = parse_frontmatter(skill_md.read_text(encoding="utf-8"))
        name = str(fm.get("name") or entry.name or "")
        description = str(fm.get("description") or "")
        total += count(catalog_line(entry.slug, name, description))
    return total


def catalog_task_tokens(
    count: Counter,
    manifest: CorpusManifest,
    corpus_dir: Path,
    *,
    matched_slug: Optional[str],
) -> TokenBreakdown:
    """Catalog per-task cost: resident catalog + full body of the fetched skill (one fetch).

    If ``matched_slug`` is None (no skill applies / none fetched), per-task disclosure is 0 — only
    the resident catalog is in context. Mirrors native's progressive disclosure, but the resident
    part is the compact catalog rather than the full-description preload.
    """
    resident = catalog_resident_tokens(count, manifest, corpus_dir)
    per_task = 0
    if matched_slug:
        entry = next((e for e in manifest.skills if e.slug == matched_slug), None)
        if entry is not None:
            skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
            per_task = count(skill_md.read_text(encoding="utf-8"))
    return TokenBreakdown(resident=resident, per_task=per_task)


# --- hierarchical-disclosure condition (sub-linear resident cost) ----------------------------
# Hierarchical disclosure keeps only C category *headers* resident; a skill's catalog line is
# disclosed only when its category is opened. Resident cost is O(C) (~sqrt(N)) instead of O(N),
# while selection still begins from resident text (the headers) — preserving catalog's compliance.
# Two tools: list_category(category) discloses one category's lines; fetch_skill(id) returns a body.

LIST_CATEGORY_SCHEMA = (
    "list_category: List the skills in one category from the resident Category Index. "
    "Pass the category label; returns that category's skills as 'id: name — description' lines. "
    'inputSchema: {"type":"object","properties":{"category":{"type":"string"}},'
    '"required":["category"]}'
)


def category_header_line(label: str, member_count: int, summary: str = "") -> str:
    """One resident category header: ``label (n): summary`` (the model picks a category from these)."""
    base = f"{label} ({member_count})"
    return f"{base}: {summary}" if summary else base


def hierarchical_resident_tokens(
    count: Counter,
    category_headers: list[tuple[str, int, str]],
) -> int:
    """Resident cost: list_category + fetch schemas + Σ category *headers* only (O(C), ~sqrt(N)).

    ``category_headers`` is a list of ``(label, member_count, summary)`` — the only skill-derived
    text that stays resident. The per-skill catalog lines are NOT resident; they are disclosed per
    category on demand (see :func:`hierarchical_task_tokens`).
    """
    total = count(LIST_CATEGORY_SCHEMA) + count(FETCH_BY_ID_SCHEMA)
    for label, n, summary in category_headers:
        total += count(category_header_line(label, n, summary))
    return total


def hierarchical_task_tokens(
    count: Counter,
    category_headers: list[tuple[str, int, str]],
    *,
    opened_category_lines: list[str],
    fetched_body: str,
) -> TokenBreakdown:
    """Per-task cost: resident headers + the opened category's disclosed lines + one fetched body.

    ``opened_category_lines`` are the ``id: name — description`` rows of the single category the agent
    opened (empty if it never opened one); ``fetched_body`` is the one skill body it fetched (empty if
    none). This is the hierarchical analogue of catalog's resident-catalog + body: here the per-skill
    lines are a *per-task* cost (only one category's worth), which is what bends resident cost
    sub-linear.
    """
    resident = hierarchical_resident_tokens(count, category_headers)
    per_task = count("\n".join(opened_category_lines)) + count(fetched_body)
    return TokenBreakdown(resident=resident, per_task=per_task)


def native_resident_tokens(count: Counter, manifest: CorpusManifest, corpus_dir: Path) -> int:
    """Native resident cost: Σ frontmatter (name + description) over all N skills (Requirement 3.3).

    This is the documented startup preload; it grows ~linearly with N. Read from the corpus files so
    it is identical content to what the native condition installs.
    """
    total = 0
    for entry in manifest.skills:
        skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
        fm, _ = parse_frontmatter(skill_md.read_text(encoding="utf-8"))
        name = str(fm.get("name") or "")
        description = str(fm.get("description") or "")
        # A small structural overhead per skill entry (the preload lists them); count name + desc.
        total += count(name) + count(description)
    return total


def native_task_tokens(
    count: Counter,
    manifest: CorpusManifest,
    corpus_dir: Path,
    *,
    matched_slug: Optional[str],
) -> TokenBreakdown:
    """Native per-task cost: resident preload + full body of the matched skill (progressive disclosure).

    If ``matched_slug`` is None (no skill applies / none invoked), per-task disclosure is 0 — only
    the resident preload is in context.
    """
    resident = native_resident_tokens(count, manifest, corpus_dir)
    per_task = 0
    if matched_slug:
        entry = next((e for e in manifest.skills if e.slug == matched_slug), None)
        if entry is not None:
            skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
            per_task = count(skill_md.read_text(encoding="utf-8"))
    return TokenBreakdown(resident=resident, per_task=per_task)
