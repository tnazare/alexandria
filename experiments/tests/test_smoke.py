"""Opt-in end-to-end smoke test (task 8.2 / task 6 of the subset rework).

Runs only when ``BENCHMARK_SMOKE=1`` and requires the live docker stack + the ``claude`` CLI + a
modern Python with ``tiktoken`` installed. It materializes a small named subset (random-20) from the
frozen committed superset, runs its first 2 tasks x 1 rep across both conditions, and asserts a
RunRecord with a token cost is produced — without asserting on the (non-deterministic) accuracy.

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
def test_smoke_subset(tmp_path):
    import json
    from harness.aggregate import load_records
    from harness.build_corpus import materialize_subset
    from harness.config import load_config
    from harness.run_experiment import run_matrix
    from harness.tasks import load_task_set, validate_against_corpus

    root = Path(__file__).resolve().parents[1]
    cfg = load_config(root / "config" / "experiment.yaml")
    subset = "random-20"

    # Materialize the subset from the committed superset (no network needed).
    corpus_dir = tmp_path / "corpus" / subset
    manifest = materialize_subset(subset, out_dir=corpus_dir,
                                  subsets_dir=root / "corpus" / "subsets",
                                  superset_dir=root / "corpus" / "superset")

    # Load the subset's frozen task set, trim to the first 2 tasks for a fast smoke.
    full = load_task_set(root / "tasks" / "subsets" / f"{subset}.json", require_min=False)
    validate_against_corpus(full, manifest.real_slugs())
    trimmed_path = tmp_path / "smoke_tasks.json"
    data = json.loads((root / "tasks" / "subsets" / f"{subset}.json").read_text())
    data["tasks"] = data["tasks"][:2]
    data["labeling_doc"] = "LABELING.md"
    (tmp_path / "LABELING.md").write_text("smoke\n", encoding="utf-8")
    trimmed_path.write_text(json.dumps(data), encoding="utf-8")
    ts = load_task_set(trimmed_path, require_min=False)

    cfg.raw["nrep"] = 1
    cfg.raw["warmup"] = 0

    records_path = run_matrix(cfg, results_root=tmp_path / "results", corpus_dir=corpus_dir,
                              subset_name=subset, task_set=ts)

    records = load_records(records_path)
    assert records, "no run records produced"
    for r in records:
        assert r.context_token_cost is not None and r.context_token_cost > 0
