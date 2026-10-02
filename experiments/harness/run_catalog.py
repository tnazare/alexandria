"""Catalog-condition run driver.

Runs one task with Claude Code configured so that:

1. the **resident Skill Catalog** (``id: name — description`` per skill) is appended to the system
   prompt — selection happens over resident text, like native, so there is no retrieval tool call to
   skip (the compliance lever the N=20 LLM runs identified);
2. a **single** local MCP tool, ``fetch_skill(id)``, served by :mod:`harness.catalog_server`, returns
   the chosen skill's body on demand — so only one body enters context, keeping per-task cost flat in
   N (the token lever).

This isolates the one variable against native: resident *compact catalog* + on-demand fetch vs.
native's resident *full descriptions* + progressive disclosure, with identical body content. No
Alexandria backend is involved; the fetch server reads the corpus directly, so the only API spend is
the Claude turn itself.

The chosen skill is the id Claude passed to ``fetch_skill`` (captured server-side); consult
compliance is "did it fetch at all". Token cost is filled from the catalog token model.
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Optional

from harness.catalog_server import CatalogServer
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
    catalog_system_prompt: str,
    id_to_slug: dict[str, str],
    count,
    experiment_version: str = "v1",
    run_id: str = "adhoc",
    claude_command: str = "claude",
    model_id: Optional[str] = None,
    effort: Optional[str] = None,
    disallowed_tools: Optional[list[str]] = None,
    seed: Optional[int] = None,
) -> RunRecord:
    """Execute one catalog-condition run and return its RunRecord.

    ``catalog_system_prompt`` is the composed directive + resident catalog (see
    :func:`harness.provision_catalog.catalog_system_prompt`). ``id_to_slug`` maps the fetched catalog
    id to a corpus slug (identity, since the id is the slug). ``count`` is a tokenizer counter used to
    fill Context_Token_Cost from the catalog token model.
    """
    server = CatalogServer(Path(corpus_dir), manifest)
    server.start()
    try:
        with tempfile.TemporaryDirectory(prefix="catalog-run-") as ws:
            workspace = Path(ws)
            mcp_cfg = write_mcp_config(workspace, server.endpoint)
            result, e2e_ms = run_claude(
                prompt, workspace, command=claude_command, model_id=model_id, effort=effort,
                mcp_config=mcp_cfg, strict_mcp_config=True,
                allowed_tools=["mcp__alexandria__fetch_skill"],
                disallowed_tools=disallowed_tools,
                append_system_prompt=catalog_system_prompt,
            )

        cap = server.capture
        consulted = cap.fetched_id is not None
        chosen_slug = id_to_slug.get(cap.fetched_id) if cap.fetched_id else None

        token_cost = _catalog_token_cost(count, manifest, Path(corpus_dir), chosen_slug=chosen_slug)

        return RunRecord(
            run_id=run_id,
            experiment_version=experiment_version,
            condition=Condition.CATALOG.value,
            library_size=library_size,
            content_hash=content_hash,
            task_id=task_id,
            gold_skill=gold_skill,
            rep=rep,
            is_warmup=is_warmup,
            chosen_skill=chosen_slug,
            resolve_candidates=None,   # the resident catalog is the candidate set (all N); recall n/a
            consulted=consulted,
            retrieval_latency_ms=cap.retrieval_latency_ms(),
            end_to_end_latency_ms=e2e_ms,
            context_token_cost=token_cost,
            reported_usage=reported_usage(result) if isinstance(result, dict) else None,
            model_id=model_id,
            model_version=(reported_model(result) if isinstance(result, dict) else None),
            seed=seed,
        )
    finally:
        server.stop()


def _catalog_token_cost(count, manifest, corpus_dir: Path, *, chosen_slug: Optional[str]) -> int:
    """Context_Token_Cost for one catalog run: resident catalog + fetched body (via the token model)."""
    from harness.measure_tokens import catalog_task_tokens

    return catalog_task_tokens(count, manifest, corpus_dir, matched_slug=chosen_slug).total
