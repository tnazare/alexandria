"""Hierarchical-disclosure run driver.

Runs one task with Claude Code configured so that:

1. the **resident Category Index** (``label (count): summary`` per category) is appended to the
   system prompt — navigation begins from resident text, like catalog/native;
2. two local MCP tools (served by :mod:`harness.hierarchical_server`) let the agent **open one
   category** (``list_category``) to see its ``id: name — description`` lines, then **fetch one body**
   (``fetch_skill``).

This tests whether the extra open-category hop keeps the catalog condition's consult compliance while
spending the sub-linear resident cost the token model promised. No Alexandria backend; the server
reads lines/bodies from the corpus + the committed categorization.

Compliance = did the agent engage the index at all (opened a category OR fetched). Model-choice = the
fetched slug. Token cost is filled from the hierarchical token model using the actually-opened
category's lines (so a run that opens the gold skill's category pays exactly that category's
disclosure, and a run that opens several pays for all of them).
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Optional

from harness.hierarchical_server import HierarchicalServer
from harness.models import Condition, CorpusManifest, RunRecord
from harness.run_alexandria import write_mcp_config
from harness.run_native import reported_model, reported_usage, run_claude


def run_task(
    *,
    task_id: str,
    prompt: str,
    gold_skill: Optional[str],
    library_size: int,
    content_hash: str,
    rep: int,
    is_warmup: bool,
    corpus_dir: Path,
    manifest: CorpusManifest,
    hierarchical_system_prompt: str,
    label_to_slugs: dict[str, list[str]],
    category_headers: list[tuple[str, int, str]],
    id_to_slug: dict[str, str],
    count,
    tier_value: str = "real",
    experiment_version: str = "v1",
    run_id: str = "adhoc",
    claude_command: str = "claude",
    model_id: Optional[str] = None,
    effort: Optional[str] = None,
    disallowed_tools: Optional[list[str]] = None,
    seed: Optional[int] = None,
) -> RunRecord:
    """Execute one hierarchical-condition run and return its RunRecord.

    ``category_headers`` is the ``(label, count, summary)`` list for the resident token cost;
    ``label_to_slugs`` lets the driver recompute the disclosed lines of whichever categories the agent
    opened, to fill the per-task token cost exactly.
    """
    server = HierarchicalServer(Path(corpus_dir), manifest, label_to_slugs, tier_value=tier_value)
    server.start()
    try:
        with tempfile.TemporaryDirectory(prefix="hier-run-") as ws:
            workspace = Path(ws)
            mcp_cfg = write_mcp_config(workspace, server.endpoint)
            result, e2e_ms = run_claude(
                prompt, workspace, command=claude_command, model_id=model_id, effort=effort,
                mcp_config=mcp_cfg, strict_mcp_config=True,
                allowed_tools=["mcp__alexandria__list_category", "mcp__alexandria__fetch_skill"],
                disallowed_tools=disallowed_tools,
                append_system_prompt=hierarchical_system_prompt,
            )

        cap = server.capture
        # Consulted = engaged the index at all (opened a category or fetched a body).
        consulted = bool(cap.opened_categories) or cap.fetched_id is not None
        chosen_slug = id_to_slug.get(cap.fetched_id) if cap.fetched_id else None

        token_cost = _hierarchical_token_cost(
            count, category_headers, corpus_dir=Path(corpus_dir), manifest=manifest,
            label_to_slugs=label_to_slugs, opened_categories=cap.opened_categories,
            chosen_slug=chosen_slug, tier_value=tier_value,
        )

        return RunRecord(
            run_id=run_id,
            experiment_version=experiment_version,
            condition=Condition.HIERARCHICAL.value,
            library_size=library_size,
            content_hash=content_hash,
            task_id=task_id,
            gold_skill=gold_skill,
            rep=rep,
            is_warmup=is_warmup,
            chosen_skill=chosen_slug,
            resolve_candidates=None,     # candidate pool is a navigated hierarchy; recall@k n/a
            consulted=consulted,
            retrieval_latency_ms=cap.retrieval_latency_ms(),
            end_to_end_latency_ms=e2e_ms,
            context_token_cost=token_cost,
            reported_usage=reported_usage(result) if isinstance(result, dict) else None,
            model_id=model_id,
            model_version=(reported_model(result) if isinstance(result, dict) else None),
            seed=seed,
            notes=f"opened={len(cap.opened_categories)}",
        )
    finally:
        server.stop()


def _hierarchical_token_cost(count, category_headers, *, corpus_dir: Path, manifest,
                             label_to_slugs: dict[str, list[str]], opened_categories: list[str],
                             chosen_slug: Optional[str], tier_value: str) -> int:
    """Context_Token_Cost: resident headers + the opened categories' disclosed lines + fetched body."""
    from harness.frontmatter import parse_frontmatter
    from harness.measure_tokens import catalog_line, hierarchical_task_tokens

    opened_lines: list[str] = []
    seen = set()
    for label in opened_categories:
        if label in seen:
            continue
        seen.add(label)
        for slug in label_to_slugs.get(label, []):
            p = corpus_dir / tier_value / slug / "SKILL.md"
            name, desc = slug, ""
            if p.is_file():
                fm, _ = parse_frontmatter(p.read_text(encoding="utf-8"))
                name = str(fm.get("name") or slug)
                desc = str(fm.get("description") or "")
            opened_lines.append(catalog_line(slug, name, desc))

    fetched_body = ""
    if chosen_slug:
        entry = next((e for e in manifest.skills if e.slug == chosen_slug), None)
        if entry is not None:
            p = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
            if p.is_file():
                fetched_body = p.read_text(encoding="utf-8")

    return hierarchical_task_tokens(
        count, category_headers, opened_category_lines=opened_lines, fetched_body=fetched_body,
    ).total
