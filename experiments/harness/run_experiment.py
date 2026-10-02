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
    The effort level is recorded alongside the model.
    """
    model_block: dict[str, object]
    if model_id:
        model_block = {"model_id": model_id, "model_version": model_version, "effort": cfg.effort}
    else:
        model_block = {"model_id": None, "model_version": None, "effort": cfg.effort,
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
        "system_prompt": {c: cfg.system_prompt(c) for c in cfg.conditions},
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


def cell_key(condition: str, library_size: int, task_id: str, rep: int) -> tuple:
    """Composite key uniquely identifying one matrix cell (for resume/skip)."""
    return (condition, int(library_size), task_id, int(rep))


def completed_cells(run_dir: Path) -> set:
    """Set of cell keys already recorded in a run's records.jsonl (empty if none).

    Used to resume a quota-interrupted run: cells already present are skipped so a re-run only
    executes the remaining matrix and appends to the same file (Requirement 7.4 — raw is the source
    of truth, so dedup is keyed off it, not a separate checkpoint).
    """
    path = run_dir / "records.jsonl"
    done = set()
    if not path.is_file():
        return done
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        done.add(cell_key(r.get("condition"), r.get("library_size"), r.get("task_id"), r.get("rep")))
    return done


# --- orchestration (live) --------------------------------------------------------------------
# The matrix executor is thin over the tested building blocks. It needs the docker stack and the
# `claude` CLI, so it is exercised by the opt-in smoke test and the manual full run — not the
# offline unit suite.


class QuotaExhaustedError(RuntimeError):
    """Raised when a Claude invocation returns no token work (quota/rate-limit/auth failure).

    The matrix aborts immediately rather than record poisoned zero-token runs, so a mid-run quota
    exhaustion can never silently corrupt a dataset (as happened in run-20260930-222648).
    """


# Side-effecting / task-execution tools denied in both conditions (decision-only runs). The agent
# may still invoke the Skill dispatcher (native) or the resolve/fetch MCP tools (alexandria) — those
# are on the per-condition allowed list — but cannot write/run/open anything, so no files are created
# and no browser preview pops. Defense-in-depth behind the stop-after-selection system prompt.
DISALLOWED_TOOLS = [
    "Bash", "Write", "Edit", "NotebookEdit", "WebFetch", "WebSearch", "Task",
]


def run_matrix(cfg: ExperimentConfig, *, results_root: Path, corpus_dir: Path, subset_name: str,
               task_set, run_id: Optional[str] = None) -> Path:
    """Execute the (condition × task × rep) matrix for ONE named subset; return the records path.

    A run operates on a single frozen subset (e.g. ``close-semantics-20``); its already-materialized
    corpus lives at ``corpus_dir``. The subset name is recorded as ``library_size``-style key via the
    manifest's size, and each record carries the subset name. Halts on provisioning failure or a
    zero-token (quota) run. Live-only imports are deferred.
    """
    import tempfile

    from harness.measure_tokens import (
        alexandria_task_tokens, native_task_tokens,
    )
    from harness.provision_alexandria import provision as provision_alexandria
    from harness.provision_native import provision as provision_native
    from harness.run_native import (
        did_real_work, extract_chosen_skill, measure_matched_skill_load, measure_preload,
        parse_claude_json, reported_model, reported_usage, run_claude,
    )
    from harness.tokenizer import Tokenizer

    resuming = run_id is not None
    run_id = run_id or new_run_id()
    run_dir = results_root / "raw" / run_id
    writer = RawWriter(run_dir)
    tok = Tokenizer(cfg.tokenizer_encoding)
    count = tok.count

    order = ordered_tasks([t.id for t in task_set.tasks], cfg.order_seed)
    by_id = {t.id: t for t in task_set.tasks}
    reps = list(range(cfg.warmup + cfg.nrep))  # rep < warmup are warmup runs

    done = completed_cells(run_dir)
    if resuming:
        print(f"Resuming {run_id}: {len(done)} cells already complete, skipping them.", flush=True)
    else:
        write_env(run_dir, build_env(cfg, model_id=cfg.model_id, model_version=None,
                                     extra={"note": "subset run", "subset": subset_name}))

    manifest = load_manifest(corpus_dir / "manifest.json")
    size = manifest.library_size
    name_to_slug = native_name_to_slug(manifest)

    for condition in cfg.conditions:
        if condition == Condition.NATIVE.value:
            _run_native_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                             name_to_slug, size, subset_name,
                             provision_native, run_claude, parse_claude_json,
                             extract_chosen_skill, reported_usage,
                             measure_matched_skill_load, measure_preload, native_task_tokens,
                             tempfile, done)
        elif condition == Condition.ALEXANDRIA.value:
            _run_alexandria_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                                 size, subset_name, provision_alexandria, alexandria_task_tokens, done)
        elif condition == Condition.CATALOG.value:
            _run_catalog_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                              size, subset_name, done)
        elif condition == Condition.HIERARCHICAL.value:
            _run_hierarchical_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count,
                                   size, subset_name, done)

    return writer.records_path


def _run_hierarchical_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, size,
                           subset_name, done) -> None:
    """Hierarchical condition: resident category index + local list_category/fetch_skill server.

    No backend. Categories are derived once (seeded k-means over committed embeddings); the resident
    index + the label->slugs map drive the system prompt and the local server respectively.
    """
    from harness.provision_hierarchical import (
        build_categorization, build_category_index_text, category_headers_for_tokens,
        hierarchical_id_to_slug, label_to_slugs as build_label_to_slugs,
    )
    from harness.provision_hierarchical import hierarchical_system_prompt as compose_hier_prompt
    from harness.run_hierarchical import run_task as run_hier_task

    pending = [(t, rep) for t in order for rep in reps
               if cell_key(Condition.HIERARCHICAL.value, size, t, rep) not in done]
    if not pending:
        print(f"  [hierarchical N={size}] all cells already complete — skipping", flush=True)
        return

    cat = build_categorization(corpus_dir, manifest, seed=cfg.padding_seed)
    index_text = build_category_index_text(cat)
    sys_prompt = compose_hier_prompt(cfg.system_prompt("hierarchical") or "", index_text)
    l2s = build_label_to_slugs(cat)
    headers = category_headers_for_tokens(cat)
    id_to_slug = hierarchical_id_to_slug(cat)
    print(f"  [hierarchical N={size}] {len(cat.categories)} categories derived", flush=True)

    for task_id in order:
        task = by_id[task_id]
        for rep in reps:
            if cell_key(Condition.HIERARCHICAL.value, size, task_id, rep) in done:
                continue
            rec = run_hier_task(
                task_id=task_id, prompt=task.prompt, gold_skill=task.gold_skill,
                library_size=size, content_hash=manifest.content_hash, rep=rep,
                is_warmup=(rep < cfg.warmup), corpus_dir=corpus_dir, manifest=manifest,
                hierarchical_system_prompt=sys_prompt, label_to_slugs=l2s,
                category_headers=headers, id_to_slug=id_to_slug, count=count,
                experiment_version=cfg.experiment_version, run_id=writer.run_dir.name,
                claude_command=cfg.claude_command, model_id=cfg.model_id, effort=cfg.effort,
                disallowed_tools=DISALLOWED_TOOLS, seed=cfg.order_seed,
            )
            rec.notes = f"subset={subset_name};{rec.notes}"
            u = rec.reported_usage or {}
            real = (int(u.get("input_tokens", 0) or 0) + int(u.get("cache_read_input_tokens", 0) or 0)
                    + int(u.get("output_tokens", 0) or 0)) > 0
            if not real:
                raise QuotaExhaustedError(
                    f"hierarchical {task_id} rep={rep} returned zero token work — likely Claude "
                    f"quota/rate-limit exhausted. Aborting to avoid poisoning the dataset. "
                    f"Resume after the quota resets; records written so far are valid.")
            print(f"  [hierarchical N={size}] {task_id} rep={rep} "
                  f"consulted={rec.consulted} chosen={rec.chosen_skill} "
                  f"tok={rec.context_token_cost} {rec.notes.split(';')[-1]}", flush=True)
            writer.write(rec)


def _run_catalog_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, size,
                      subset_name, done) -> None:
    """Catalog condition: resident catalog in the system prompt + a local on-demand fetch server.

    No backend and no per-skill workspace install — the catalog text is built once from the corpus
    and the single fetch is served locally. So this cell only needs the corpus files already
    materialized for the subset.
    """
    from harness.provision_catalog import build_catalog_text, catalog_id_to_slug
    from harness.provision_catalog import catalog_system_prompt as compose_catalog_prompt
    from harness.run_catalog import run_task as run_catalog_task

    pending = [(t, rep) for t in order for rep in reps
               if cell_key(Condition.CATALOG.value, size, t, rep) not in done]
    if not pending:
        print(f"  [catalog N={size}] all cells already complete — skipping", flush=True)
        return

    catalog_text = build_catalog_text(corpus_dir, manifest)  # halts if a body is missing
    sys_prompt = compose_catalog_prompt(cfg.system_prompt("catalog") or "", catalog_text)
    id_to_slug = catalog_id_to_slug(manifest)

    for task_id in order:
        task = by_id[task_id]
        for rep in reps:
            if cell_key(Condition.CATALOG.value, size, task_id, rep) in done:
                continue
            rec = run_catalog_task(
                task_id=task_id, prompt=task.prompt, gold_skill=task.gold_skill,
                library_size=size, content_hash=manifest.content_hash, rep=rep,
                is_warmup=(rep < cfg.warmup), corpus_dir=corpus_dir, manifest=manifest,
                catalog_system_prompt=sys_prompt, id_to_slug=id_to_slug, count=count,
                experiment_version=cfg.experiment_version, run_id=writer.run_dir.name,
                claude_command=cfg.claude_command, model_id=cfg.model_id, effort=cfg.effort,
                disallowed_tools=DISALLOWED_TOOLS, seed=cfg.order_seed,
            )
            rec.notes = f"subset={subset_name}"
            u = rec.reported_usage or {}
            real = (int(u.get("input_tokens", 0) or 0) + int(u.get("cache_read_input_tokens", 0) or 0)
                    + int(u.get("output_tokens", 0) or 0)) > 0
            if not real:
                raise QuotaExhaustedError(
                    f"catalog {task_id} rep={rep} returned zero token work — likely Claude "
                    f"quota/rate-limit exhausted. Aborting to avoid poisoning the dataset. "
                    f"Resume after the quota resets; records written so far are valid.")
            print(f"  [catalog N={size}] {task_id} rep={rep} "
                  f"consulted={rec.consulted} chosen={rec.chosen_skill} "
                  f"tok={rec.context_token_cost}", flush=True)
            writer.write(rec)


def _run_native_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, name_to_slug,
                     size, subset_name, provision_native, run_claude, parse_claude_json,
                     extract_chosen_skill, reported_usage, measure_matched_skill_load,
                     measure_preload, native_task_tokens, tempfile, done) -> None:
    # Resume: if every (task, rep) in this cell is already recorded, skip provisioning entirely.
    pending = [(t, rep) for t in order for rep in reps
               if cell_key(Condition.NATIVE.value, size, t, rep) not in done]
    if not pending:
        print(f"  [native N={size}] all cells already complete — skipping", flush=True)
        return
    with tempfile.TemporaryDirectory(prefix="native-ws-") as ws:
        workspace = Path(ws)
        provision_native(corpus_dir, workspace)  # halts on mismatch
        skills_root = workspace / ".claude" / "skills"
        preload_ms, _ = measure_preload(skills_root)
        for task_id in order:
            task = by_id[task_id]
            for rep in reps:
                if cell_key(Condition.NATIVE.value, size, task_id, rep) in done:
                    continue
                result, e2e = run_claude(task.prompt, workspace, command=cfg.claude_command,
                                         model_id=cfg.model_id, effort=cfg.effort,
                                         allowed_tools=["Skill"],
                                         disallowed_tools=DISALLOWED_TOOLS,
                                         append_system_prompt=cfg.system_prompt("native"),
                                         stream=True)
                from harness.run_native import did_real_work
                if not did_real_work(result):
                    raise QuotaExhaustedError(
                        f"native {task_id} rep={rep} returned zero token work — likely Claude "
                        f"quota/rate-limit exhausted. Aborting to avoid poisoning the dataset. "
                        f"Resume after the quota resets; records written so far are valid.")
                chosen_name = extract_chosen_skill(result)
                chosen_slug = name_to_slug.get(chosen_name) if chosen_name else None
                consulted = chosen_name is not None
                print(f"  [native N={size}] {task_id} rep={rep} "
                      f"consulted={consulted} chosen={chosen_slug}", flush=True)
                retrieval_ms = None
                if chosen_slug:
                    entry = next((e for e in manifest.skills if e.slug == chosen_slug), None)
                    if entry:
                        skill_md = corpus_dir / entry.tier.value / entry.slug / "SKILL.md"
                        retrieval_ms, _ = measure_matched_skill_load(skill_md)
                tokens = native_task_tokens(count, manifest, corpus_dir, matched_slug=chosen_slug)
                from harness.run_native import reported_model
                writer.write(RunRecord(
                    run_id=writer.run_dir.name, experiment_version=cfg.experiment_version,
                    condition=Condition.NATIVE.value, library_size=size,
                    content_hash=manifest.content_hash, task_id=task_id, gold_skill=task.gold_skill,
                    rep=rep, is_warmup=(rep < cfg.warmup), chosen_skill=chosen_slug,
                    resolve_candidates=None, consulted=consulted, retrieval_latency_ms=retrieval_ms,
                    end_to_end_latency_ms=e2e, native_preload_ms=preload_ms,
                    context_token_cost=tokens.total, reported_usage=reported_usage(result),
                    model_id=cfg.model_id, model_version=reported_model(result),
                    seed=cfg.order_seed, notes=f"subset={subset_name}",
                ))


def _run_alexandria_cell(cfg, manifest, corpus_dir, order, by_id, reps, writer, count, size,
                         subset_name, provision_alexandria, alexandria_task_tokens, done) -> None:
    from harness.alexandria_client import AlexandriaClient
    from harness.run_alexandria import run_task

    # Resume: if every (task, rep) in this cell is already recorded, skip provisioning entirely.
    pending = [(t, rep) for t in order for rep in reps
               if cell_key(Condition.ALEXANDRIA.value, size, t, rep) not in done]
    if not pending:
        print(f"  [alexandria N={size}] all cells already complete — skipping", flush=True)
        return

    acfg = cfg.alexandria()
    provision_alexandria(acfg, corpus_dir)  # halts on failure
    # Mint a service token so the proxy can authenticate to the MCP endpoint. The provisioning
    # already used an Org_Admin OIDC token; here we reuse it as the upstream bearer for simplicity.
    client = AlexandriaClient(acfg)
    upstream = f"{acfg.host}/api/agent/v1/mcp"
    bearer = client.token()
    # The resolver returns each skill's display *name*, which provisioning set to titleize(slug)
    # (NOT the manifest's harvested frontmatter name). So the id map must key on the same titleize
    # the provisioner used, or resolve candidates won't map back to slugs.
    from harness.provision_alexandria import _titleize
    id_to_slug = build_id_to_slug(manifest, name_for_slug=_titleize)
    for task_id in order:
        task = by_id[task_id]
        for rep in reps:
            if cell_key(Condition.ALEXANDRIA.value, size, task_id, rep) in done:
                continue
            rec = run_task(
                task_id=task_id, prompt=task.prompt, gold_skill=task.gold_skill,
                library_size=size, content_hash=manifest.content_hash, rep=rep,
                is_warmup=(rep < cfg.warmup), upstream_mcp_url=upstream, service_token=bearer,
                id_to_slug=id_to_slug, experiment_version=cfg.experiment_version,
                run_id=writer.run_dir.name, claude_command=cfg.claude_command,
                model_id=cfg.model_id, effort=cfg.effort,
                append_system_prompt=cfg.system_prompt("alexandria"),
                disallowed_tools=DISALLOWED_TOOLS, seed=cfg.order_seed,
                manifest=manifest, corpus_dir=corpus_dir, count=count,
            )
            rec.notes = f"subset={subset_name}"
            u = rec.reported_usage or {}
            real = (int(u.get("input_tokens", 0) or 0) + int(u.get("cache_read_input_tokens", 0) or 0)
                    + int(u.get("output_tokens", 0) or 0)) > 0
            if not real:
                raise QuotaExhaustedError(
                    f"alexandria {task_id} rep={rep} returned zero token work — likely Claude "
                    f"quota/rate-limit exhausted. Aborting to avoid poisoning the dataset. "
                    f"Resume after the quota resets; records written so far are valid.")
            print(f"  [alexandria N={size}] {task_id} rep={rep} "
                  f"chosen={rec.chosen_skill} cand={len(rec.resolve_candidates or [])} "
                  f"tok={rec.context_token_cost}", flush=True)
            writer.write(rec)


def main() -> int:
    import argparse

    from harness.build_corpus import materialize_subset
    from harness.config import load_config
    from harness.tasks import load_task_set, validate_against_corpus

    parser = argparse.ArgumentParser(description="Run the skill-retrieval benchmark for one subset.")
    parser.add_argument("--config", default="config/experiment.yaml")
    parser.add_argument("--subset", default=None,
                        help="Named subset to run, e.g. close-semantics-20 / random-100 / "
                             "sparse-semantics-20.")
    parser.add_argument("--nrep", type=int, default=None)
    parser.add_argument("--warmup", type=int, default=None)
    parser.add_argument("--results", default="results")
    parser.add_argument("--corpus", default="corpus")
    parser.add_argument("--resume", default=None, metavar="RUN_ID",
                        help="Resume an interrupted run: skip cells already in "
                             "results/raw/<RUN_ID>/records.jsonl and append the rest.")
    parser.add_argument("--print-env", action="store_true",
                        help="Print the captured env and exit (no runs).")
    parser.add_argument("--materialize-only", action="store_true",
                        help="Only materialize the subset corpus, then exit.")
    args = parser.parse_args()

    cfg = load_config(args.config)
    if args.nrep is not None:
        cfg.raw["nrep"] = args.nrep
    if args.warmup is not None:
        cfg.raw["warmup"] = args.warmup

    subset_name = args.subset

    # On resume, inherit matrix shape + subset from the original run's env.json.
    if args.resume:
        env_path = Path(args.results) / "raw" / args.resume / "env.json"
        if not env_path.is_file():
            print(f"ERROR: --resume {args.resume}: no env.json at {env_path}", flush=True)
            return 2
        orig = json.loads(env_path.read_text(encoding="utf-8"))
        cfg.raw["nrep"] = orig["nrep"]
        cfg.raw["warmup"] = orig["warmup"]
        cfg.raw["conditions"] = orig["conditions"]
        subset_name = orig.get("subset", subset_name)
        print(f"Resume: inheriting from {args.resume} (subset={subset_name} nrep={orig['nrep']} "
              f"warmup={orig['warmup']})", flush=True)

    env = build_env(cfg, model_id=cfg.model_id, model_version=None, extra={"subset": subset_name})
    if args.print_env:
        print(json.dumps(env, indent=2))
        return 0

    if not subset_name:
        print("ERROR: --subset is required (e.g. --subset close-semantics-20).", flush=True)
        return 2

    # Materialize the chosen subset's corpus from the frozen superset.
    corpus_dir = Path(args.corpus) / subset_name
    manifest = materialize_subset(subset_name, out_dir=corpus_dir,
                                  experiment_version=cfg.experiment_version)
    print(f"corpus subset '{subset_name}': {corpus_dir} ({manifest.library_size} skills)")
    if args.materialize_only:
        return 0

    # Load this subset's frozen task set; validate every gold label is in the subset.
    task_set = load_task_set(f"tasks/subsets/{subset_name}.json", require_min=False)
    validate_against_corpus(task_set, manifest.real_slugs())

    print(f"Running subset '{subset_name}': conditions={cfg.conditions} "
          f"nrep={cfg.nrep} warmup={cfg.warmup} tasks={len(task_set.tasks)}")
    aborted = False
    try:
        records_path = run_matrix(cfg, results_root=Path(args.results), corpus_dir=corpus_dir,
                                  subset_name=subset_name, task_set=task_set, run_id=args.resume)
    except QuotaExhaustedError as e:
        aborted = True
        print(f"\n*** ABORTED: {e}", flush=True)
        # Find the most recent run dir so we can still aggregate the valid partial records.
        raw_root = Path(args.results) / "raw"
        run_dirs = sorted(raw_root.glob("run-*"), key=lambda p: p.stat().st_mtime)
        records_path = (run_dirs[-1] / "records.jsonl") if run_dirs else None
        if not records_path or not records_path.is_file():
            return 2
    print(f"Raw records: {records_path}")

    # Aggregate immediately so results are ready to write up.
    from harness.aggregate import aggregate_all, load_records, write_aggregates

    run_id = records_path.parent.name
    aggs = aggregate_all(load_records(records_path), k=cfg.k)
    jp, cp = write_aggregates(aggs, Path(args.results) / "computed" / run_id)
    print(f"Aggregates: {jp} , {cp}")
    if aborted:
        print("NOTE: run aborted early (quota). Aggregates cover only the valid completed records.")
        return 2
    print("Now copy RESULTS_TEMPLATE.md to results/<run-id>.md and fill it in.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
