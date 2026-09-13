"""Accuracy scoring from raw RunRecords (Requirements 5.2, 5.3, 5.4, 5.5).

- **Model_Choice_Accuracy** (primary): the agent used the Gold_Skill. For a "no skill applies" task
  (``gold_skill is None``), correct means the agent used no skill (``chosen_skill is None``).
- **Retrieval_Recall_at_K** (secondary): the Gold_Skill appears in the top-K candidates surfaced.
  Not applicable to "no skill applies" tasks (they have no gold to recall), which are excluded from
  the recall denominator.

Both are computed from objective, logged signals already on the RunRecord (never from grading output
text). A deterministic (seeded) bootstrap gives a confidence interval over the runs.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Optional

from harness.models import RunRecord


def is_model_choice_correct(rec: RunRecord) -> bool:
    """Whether the agent used the Gold_Skill (or correctly used none for a no-skill-applies task)."""
    if rec.gold_skill is None:
        return rec.chosen_skill is None
    return rec.chosen_skill == rec.gold_skill


def is_recall_hit(rec: RunRecord, k: int) -> Optional[bool]:
    """Whether the Gold_Skill is in the top-K surfaced candidates. None if not applicable.

    Returns None for no-skill-applies tasks (no gold to recall) and when candidates were not captured
    for this run (so the run is excluded from the recall denominator rather than counted as a miss).
    """
    if rec.gold_skill is None:
        return None
    if rec.resolve_candidates is None:
        return None
    return rec.gold_skill in rec.resolve_candidates[:k]


@dataclass(frozen=True)
class ScoreResult:
    n: int                 # number of runs contributing
    successes: int
    rate: float
    ci_low: float
    ci_high: float


def _bootstrap_ci(outcomes: list[int], *, seed: int, iters: int = 2000,
                  alpha: float = 0.05) -> tuple[float, float]:
    """Percentile bootstrap CI for the mean of 0/1 outcomes. Deterministic for a fixed seed."""
    if not outcomes:
        return (0.0, 0.0)
    rng = random.Random(seed)
    n = len(outcomes)
    means = []
    for _ in range(iters):
        sample = [outcomes[rng.randrange(n)] for _ in range(n)]
        means.append(sum(sample) / n)
    means.sort()
    lo = means[int((alpha / 2) * iters)]
    hi = means[min(iters - 1, int((1 - alpha / 2) * iters))]
    return (lo, hi)


def score_model_choice(records: list[RunRecord], *, seed: int = 0) -> ScoreResult:
    """Model_Choice_Accuracy over the given (already-filtered) runs, with a bootstrap CI."""
    outcomes = [1 if is_model_choice_correct(r) else 0 for r in records]
    successes = sum(outcomes)
    n = len(outcomes)
    rate = successes / n if n else 0.0
    lo, hi = _bootstrap_ci(outcomes, seed=seed)
    return ScoreResult(n=n, successes=successes, rate=rate, ci_low=lo, ci_high=hi)


def score_recall_at_k(records: list[RunRecord], k: int, *, seed: int = 0) -> ScoreResult:
    """Retrieval_Recall_at_K over applicable runs (gold present + candidates captured)."""
    outcomes = []
    for r in records:
        hit = is_recall_hit(r, k)
        if hit is not None:
            outcomes.append(1 if hit else 0)
    successes = sum(outcomes)
    n = len(outcomes)
    rate = successes / n if n else 0.0
    lo, hi = _bootstrap_ci(outcomes, seed=seed)
    return ScoreResult(n=n, successes=successes, rate=rate, ci_low=lo, ci_high=hi)
