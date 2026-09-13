"""Alexandria-condition run driver (Requirements 4.1, 4.2, 5.2, 5.4, 3.4, 6.4).

Runs one task with Claude Code configured to reach the Alexandria MCP endpoint **through the local
capture proxy** (:mod:`harness.mcp_proxy`). The proxy records the ``resolve_skill`` candidates, the
``fetch_skill`` ``skillVersionId``, and per-call HTTP timings; the driver reads them back after the
Claude invocation to build a :class:`RunRecord`.

Claude Code is launched in a temp workspace with a generated ``.mcp.json`` pointing at the proxy
(``type: http`` + Bearer token). The proxy forwards to the real endpoint with the minted service
token, so end-to-end auth is exercised.

Pure result-mapping is shared with :mod:`harness.run_native`; the subprocess call is thin.
"""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Optional

from harness.mcp_proxy import McpCaptureProxy
from harness.models import Condition, RunRecord
from harness.run_native import parse_claude_json, reported_usage, run_claude


def write_mcp_config(workspace: Path, endpoint: str, bearer: str = "proxy") -> Path:
    """Write a Claude Code ``.mcp.json`` pointing at the proxy endpoint."""
    cfg = {
        "mcpServers": {
            "alexandria": {
                "type": "http",
                "url": endpoint,
                "headers": {"Authorization": f"Bearer {bearer}"},
            }
        }
    }
    path = workspace / ".mcp.json"
    path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return path


def map_candidates_to_slugs(candidates: Optional[list[str]],
                            id_to_slug: dict[str, str]) -> Optional[list[str]]:
    """Map captured resolve candidate ids/names to gold slugs via the provision-time map.

    Unknown identifiers are dropped (they cannot be scored against gold slugs). Order preserved.
    """
    if candidates is None:
        return None
    out: list[str] = []
    for ident in candidates:
        slug = id_to_slug.get(ident)
        if slug:
            out.append(slug)
    return out


def run_task(
    *,
    task_id: str,
    prompt: str,
    gold_skill: Optional[str],
    library_size: int,
    content_hash: str,
    rep: int,
    is_warmup: bool,
    upstream_mcp_url: str,
    service_token: str,
    id_to_slug: dict[str, str],
    experiment_version: str = "v1",
    run_id: str = "adhoc",
    claude_command: str = "claude",
    model_id: Optional[str] = None,
    seed: Optional[int] = None,
) -> RunRecord:
    """Execute one Alexandria-condition run and return its RunRecord.

    ``id_to_slug`` maps resolver identifiers (skillVersionId and/or display name) to corpus slugs,
    built at provision time so accuracy can be scored against gold slugs.
    """
    proxy = McpCaptureProxy(upstream_mcp_url, bearer_token=service_token)
    proxy.start()
    try:
        with tempfile.TemporaryDirectory(prefix="alx-run-") as ws:
            workspace = Path(ws)
            write_mcp_config(workspace, proxy.endpoint)
            result, e2e_ms = run_claude(prompt, workspace, command=claude_command, model_id=model_id)

        cap = proxy.capture
        chosen_slug = id_to_slug.get(cap.fetch_version_id) if cap.fetch_version_id else None
        candidate_slugs = map_candidates_to_slugs(cap.resolve_candidates, id_to_slug)

        return RunRecord(
            run_id=run_id,
            experiment_version=experiment_version,
            condition=Condition.ALEXANDRIA.value,
            library_size=library_size,
            content_hash=content_hash,
            task_id=task_id,
            gold_skill=gold_skill,
            rep=rep,
            is_warmup=is_warmup,
            chosen_skill=chosen_slug,
            resolve_candidates=candidate_slugs,
            retrieval_latency_ms=cap.retrieval_latency_ms(),
            end_to_end_latency_ms=e2e_ms,
            reported_usage=reported_usage(result),
            model_id=model_id,
            model_version=(result.get("model") if isinstance(result, dict) else None),
            seed=seed,
        )
    finally:
        proxy.stop()
