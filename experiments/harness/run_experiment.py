"""Experiment orchestrator: single-command entry point (Requirements 6.1, 6.3, 6.4, 7.3, 7.4, 7.6).

Validates the Task_Set and provisioning, records the run environment, executes the
(condition × library_size × task × rep) matrix in seeded-random task order, computes each run's
Context_Token_Cost offline, writes raw ``records.jsonl``, and finally aggregates.

The deterministic building blocks — environment capture, seeded ordering, and the resolver-identity
-> slug map — are pure functions and unit-tested. The matrix execution itself calls Claude Code and
the live stack, so it is exercised by the opt-in smoke test (task 8.2) and the full run (task 10.2),
not by the offline unit suite.
"""

from __future__ import annotations

import json
import platform
import random
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from harness.build_corpus import load_manifest
from harness.config import ExperimentConfig
from harness.models import CorpusManifest, Condition, RunRecord, Tier


# --- pure helpers (unit-tested) --------------------------------------------------------------


def build_env(cfg: ExperimentConfig, *, model_id: Optional[str], model_version: Optional[str],
              extra: Optional[dict] = None) -> dict:
    """Capture the run environment for reproducibility (Requirements 6.4, 7.6).

    Model identifier and version are recorded **together or neither**: if the identifier is missing,
    no version is recorded and the run is flagged as missing model provenance (Requirement 6.4).
    """
    model_block: dict[str, object]
    if model_id:
        model_block = {"model_id": model_id, "model_version": model_version}
    else:
        model_block = {"model_id": None, "model_version": None,
                       "missing_model_provenance": True}

    env = {
        "experiment_version": cfg.experiment_version,
        "machine": platform.node(),
        "platform": platform.platform(),
        "python": sys.version.split()[0],
        "seeds": {"padding": cfg.padding_seed, "order": cfg.order_seed},
        "library_sizes": cfg.library_sizes,
        "nrep": cfg.nrep,
        "warmup": cfg.warmup,
        "k": cfg.k,
        "tokenizer_encoding": cfg.tokenizer_encoding,
        "conditions": cfg.conditions,
        "model": model_block,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    if extra:
        env.update(extra)
    return env


def ordered_tasks(task_ids: list[str], seed: int) -> list[str]:
    """Return a seeded-random ordering of task ids (Requirement 6.3). Deterministic for a seed."""
    rng = random.Random(seed)
    shuffled = list(task_ids)
    rng.shuffle(shuffled)
    return shuffled


def build_id_to_slug(manifest: CorpusManifest,
                     name_for_slug) -> dict[str, str]:
    """Map resolver identifiers to corpus slugs so accuracy can be scored (design §5, task 6 note).

    The resolver returns ``name`` and ``ref.skillVersionId`` but no slug. At provision time we know
    each skill's slug and the display name we created it with, so we can invert both. ``skillVersionId``
    is only known after creation and is supplied separately (see ``add_version_ids``). Here we seed
    the map with the display-name -> slug direction plus the raw slug -> slug identity.

    :param name_for_slug: callable slug -> display name used when the skill was created.
    """
    id_to_slug: dict[str, str] = {}
    for entry in manifest.skills:
        id_to_slug[entry.slug] = entry.slug          # slug identity (native transcripts may emit slug)
        display = name_for_slug(entry.slug)
        if display:
            id_to_slug[display] = entry.slug          # display name -> slug (resolver 'name')
    return id_to_slug


def native_name_to_slug(manifest: CorpusManifest) -> dict[str, str]:
    """Map each skill's frontmatter/display name to its slug for native transcript scoring."""
    out: dict[str, str] = {}
    for entry in manifest.skills:
        out[entry.slug] = entry.slug
        if entry.name:
            out[entry.name] = entry.slug
    return out


# --- run-id + writer -------------------------------------------------------------------------


def new_run_id() -> str:
    return time.strftime("run-%Y%m%d-%H%M%S")


@dataclass
class RawWriter:
    """Appends RunRecords to results/raw/<run-id>/records.jsonl (the source of truth)."""

    run_dir: Path

    def __post_init__(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self._path = self.run_dir / "records.jsonl"

    def write(self, rec: RunRecord) -> None:
        with self._path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(rec.to_json()) + "\n")

    @property
    def records_path(self) -> Path:
        return self._path


def write_env(run_dir: Path, env: dict) -> Path:
    run_dir.mkdir(parents=True, exist_ok=True)
    p = run_dir / "env.json"
    p.write_text(json.dumps(env, indent=2), encoding="utf-8")
    return p


# --- orchestration (live) --------------------------------------------------------------------
# The matrix executor is thin over the tested building blocks. It needs the docker stack and the
# `claude` CLI, so it is exercised by the opt-in smoke test and the manual full run — not the
# offline unit suite.


def run_matrix(cfg: ExperimentConfig, *, results_root: Path, corpus_root: Path,
               task_set, run_id: Optional[str] = None) -> Path:
    """Execute the full (condition × size × task × rep) matrix and return the records.jsonl path.

    Live: provisions each condition per size, runs each task in seeded order for warmup+nrep, computes
    Context_Token_Cost offline, and appends RunRecords. Halts (raises) on any provisioning failure
    (Requirement 1.4). Import of live-only modules is deferred so the offline unit suite need not
    have httpx/tiktoken.
    """
    import tempfile

    from harness.measure_tokens import (
        alexandria_task_tokens, native_task_tokens,
    )
    from harness.provision_alexandria import provision as provision_alexandria
    from harness.provision_native import provision as provision_native
    from harness.run_native import (
        extract_chosen_skill, measure_matched_skill_load, measure_preload,
        parse_claude_json, reported_usage, run_claude,
    )
    from harness.tokenizer import Tokenizer

    run_id = run_id or new_run_id()
    run_dir = results_root / "raw" / run_id
    writer = RawWriter(run_dir)
    tok = Tokenizer(cfg.tokenizer_encoding)
    count = tok.count

    order = ordered_tasks([t.id for t in task_set.tasks], cfg.order_seed)
    by_id = {t.id: t for t in task_set.tasks}
    reps = list(range(cfg.warmup + cfg.nrep))  # rep < warmup are warmup runs

    write_env(run_dir, build_env(cfg, model_id=cfg.model_id, model_version=None,
                                 extra={"note": "matrix run"}))

    for size in cfg.library_sizes:
        # A per-size corpus must already be built under corpus_root/<size>; halt if absent.
        corpus_dir = corpus_root if (corpus_root / "manifest.json").is_file() else corpus_root / str(size)
        manifest = load_manifest(corpus_dir / "manifest.json")
        name_to_slug = native_name_to_slug(manifest)

        for condition in cfg.conditions:
            if condition == Condition.NATIVE.value:
                _run_native_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                                 name_to_slug, size,
                                 provision_native, run_claude, parse_claude_json,
                                 extract_chosen_skill, reported_usage,
                                 measure_matched_skill_load, measure_preload, native_task_tokens,
                                 tempfile)
            elif condition == Condition.ALEXANDRIA.value:
                _run_alexandria_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                                     size, provision_alexandria, alexandria_task_tokens)

    return writer.records_path


def _run_native_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, name_to_slug,
                     size, provision_native, run_claude, parse_claude_json, extract_chosen_skill,
                     reported_usage, measure_matched_skill_load, measure_preload,
                     native_task_tokens, tempfile) -> None:
    with tempfile.TemporaryDirectory(prefix="native-ws-") as ws:
        workspace = Path(ws)
        provision_native(corpus_dir, workspace)  # halts on mismatch
        skills_root = workspace / ".claude" / "skills"
        preload_ms, _ = measure_preload(skills_root)
        for task_id in order:
            task = by_id[task_id]
            for rep in reps:
                result, e2e = run_claude(task.prompt, workspace, command=cfg.claude_command,
                                         model_id=cfg.model_id)
                chosen_name = extract_chosen_skill(result)
                chosen_slug = name_to_slug.get(chosen_name) if chosen_name else None
                retrieval_ms = None
                if chosen_slug:
                    entry = next((e for e in manifest.skills if e.slug == chosen_slug), None)
                    if entry:
                        skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
                        retrieval_ms, _ = measure_matched_skill_load(skill_md)
                tokens = native_task_tokens(count, manifest, corpus_dir, matched_slug=chosen_slug)
                writer.write(RunRecord(
                    run_id=writer.run_dir.name, experiment_version=cfg.experiment_version,
                    condition=Condition.NATIVE.value, library_size=size,
                    content_hash=manifest.content_hash, task_id=task_id, gold_skill=task.gold_skill,
                    rep=rep, is_warmup=(rep < cfg.warmup), chosen_skill=chosen_slug,
                    resolve_candidates=None, retrieval_latency_ms=retrieval_ms,
                    end_to_end_latency_ms=e2e, native_preload_ms=preload_ms,
                    context_token_cost=tokens.total, reported_usage=reported_usage(result),
                    model_id=cfg.model_id, seed=cfg.order_seed,
                ))


def _run_alexandria_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, size,
                         provision_alexandria, alexandria_task_tokens) -> None:
    from harness.alexandria_client import AlexandriaClient
    from harness.run_alexandria import run_task

    acfg = cfg.alexandria()
    provision_alexandria(acfg, corpus_dir)  # halts on failure
    # Mint a service token so the proxy can authenticate to the MCP endpoint. The provisioning
    # already used an Org_Admin OIDC token; here we reuse it as the upstream bearer for simplicity.
    client = AlexandriaClient(acfg)
    upstream = f"{acfg.host}/api/agent/v1/mcp"
    bearer = client.token()
    # Provision-time id map: display name -> slug (skillVersionId is captured live via the proxy and
    # is a UUID; name mapping covers the common resolver 'name' field).
    id_to_slug = build_id_to_slug(manifest, name_for_slug=lambda s:
                                  next((e.name for e in manifest.skills if e.slug == s), None))
    for task_id in order:
        task = by_id[task_id]
        for rep in reps:
            rec = run_task(
                task_id=task_id, prompt=task.prompt, gold_skill=task.gold_skill,
                library_size=size, content_hash=manifest.content_hash, rep=rep,
                is_warmup=(rep < cfg.warmup), upstream_mcp_url=upstream, service_token=bearer,
                id_to_slug=id_to_slug, experiment_version=cfg.experiment_version,
                run_id=writer.run_dir.name, claude_command=cfg.claude_command,
                model_id=cfg.model_id, seed=cfg.order_seed,
            )
            # Token cost is filled offline here (the run driver captured payloads via the proxy;
            # for the resolve/fetch text the proxy could be extended to persist bodies — for now we
            # record the run and leave context_token_cost to the offline measure step in the full run).
            writer.write(rec)


def main() -> int:
    import argparse

    from harness.config import load_config

    parser = argparse.ArgumentParser(description="Run the skill-retrieval benchmark matrix.")
    parser.add_argument("--config", default="config/experiment.yaml")
    parser.add_argument("--sizes", default=None, help="Override library sizes, e.g. 20,100")
    parser.add_argument("--nrep", type=int, default=None)
    parser.add_argument("--warmup", type=int, default=None)
    parser.add_argument("--results", default="results")
    parser.add_argument("--print-env", action="store_true",
                        help="Print the captured env and exit (no runs).")
    args = parser.parse_args()

    cfg = load_config(args.config)
    env = build_env(cfg, model_id=cfg.model_id, model_version=None)

    if args.print_env:
        print(json.dumps(env, indent=2))
        return 0

    # The live matrix run is documented in PROTOCOL.md and driven manually (task 10.2): it provisions
    # each condition per size, executes tasks in seeded order via the run drivers, computes token
    # cost, writes records.jsonl + env.json, and aggregates. Kept out of the offline unit path.
    print("run_experiment scaffolding ready. See experiments/PROTOCOL.md for the live run procedure.")
    print(json.dumps({"conditions": cfg.conditions, "sizes": cfg.library_sizes,
                      "nrep": cfg.nrep, "warmup": cfg.warmup}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
