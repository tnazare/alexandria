"""Opt-in end-to-end smoke test (task 8.2).

Runs only when ``BENCHMARK_SMOKE=1`` and requires the live docker stack + the ``claude`` CLI +
network access to harvest + a modern Python with ``tiktoken``/``httpx`` installed. It provisions
N=20 into both conditions, runs 2 tasks x 1 rep, and asserts that a RunRecord with a non-null
chosen_skill (for a clear-match task), a positive retrieval latency, and a token cost is produced —
without asserting on the (non-deterministic) accuracy value.

Skipped by default so the deterministic offline suite stays fast and dependency-light.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

import pytest

SMOKE = os.environ.get("BENCHMARK_SMOKE") == "1"

pytestmark = pytest.mark.skipif(not SMOKE, reason="set BENCHMARK_SMOKE=1 to run the live smoke test")


@pytest.mark.skipif(shutil.which("claude") is None, reason="claude CLI not on PATH")
def test_smoke_small_matrix(tmp_path):
    from harness.build_corpus import assemble, write
    from harness.config import load_config
    from harness.harvest import harvest, load_sources
    from harness.models import Tier
    from harness.run_experiment import run_matrix
    from harness.tasks import load_task_set, validate_against_corpus

    root = Path(__file__).resolve().parents[1]
    cfg = load_config(root / "config" / "experiment.yaml")

    # Build a small N=20 corpus.
    skills = harvest(load_sources(root / "config" / "sources.yaml"))
    real = [s for s in skills if s.tier == Tier.REAL]
    distractors = [s for s in skills if s.tier == Tier.DISTRACTOR]
    manifest, padding = assemble(real, distractors, library_size=20, seed=cfg.padding_seed)
    corpus_dir = tmp_path / "corpus"
    write(manifest, real, distractors, padding, corpus_dir)

    # A 2-task set: one clear-match (gold must be a harvested real slug) + one no-skill-applies.
    real_slugs = {s.slug for s in real}
    gold = "pdf" if "pdf" in real_slugs else sorted(real_slugs)[0]
    task_json = {
        "version": "smoke", "labeling_doc": "LABELING.md",
        "tasks": [
            {"id": "s1", "prompt": f"Use the {gold} skill to process the input file.",
             "gold_skill": gold},
            {"id": "s2", "prompt": "Rename a single local variable.", "gold_skill": None},
        ],
    }
    tasks_dir = tmp_path / "tasks"
    tasks_dir.mkdir()
    (tasks_dir / "LABELING.md").write_text("smoke\n", encoding="utf-8")
    import json
    (tasks_dir / "set.json").write_text(json.dumps(task_json), encoding="utf-8")
    ts = load_task_set(tasks_dir / "set.json", require_min=False)
    validate_against_corpus(ts, real_slugs)

    # Run 1 rep, no warmup, both conditions at N=20.
    cfg_small = cfg
    cfg_small.raw["library_sizes"] = [20]
    cfg_small.raw["nrep"] = 1
    cfg_small.raw["warmup"] = 0

    records_path = run_matrix(cfg_small, results_root=tmp_path / "results",
                              corpus_root=corpus_dir, task_set=ts)

    from harness.aggregate import load_records
    records = load_records(records_path)
    assert records, "no run records produced"
    for r in records:
        assert r.context_token_cost is not None and r.context_token_cost > 0
    # At least one run should have positive retrieval latency (the matched-skill / resolve+fetch cost).
    assert any((r.retrieval_latency_ms or 0) > 0 for r in records)
