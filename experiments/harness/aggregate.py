"""Aggregation of raw RunRecords into per-cell summaries (Requirements 2.3, 4.4, 6.2, 6.5, 9.6).

Reads ``records.jsonl`` and computes, per (metric, condition, library_size):
- token cost: median + IQR of Context_Token_Cost, plus mean reported usage;
- retrieval latency: median + IQR, split cold vs warm;
- accuracy: model-choice rate + bootstrap CI, and recall@K rate + CI.

Warm-up runs are **excluded from the primary statistics** but retained in the raw input and can be
reported separately (Requirement 6.5). Everything recomputes from the raw file, so re-running yields
identical output (Requirement 7.4).
"""

from __future__ import annotations

import json
import statistics
from dataclasses import asdict
from pathlib import Path
from typing import Iterable, Optional

from harness.models import Aggregate, RunRecord
from harness.score import score_model_choice, score_recall_at_k


def load_records(path: str | Path) -> list[RunRecord]:
    records = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            records.append(RunRecord.from_json(json.loads(line)))
    return records


def measured(records: Iterable[RunRecord]) -> list[RunRecord]:
    """Only non-warmup runs contribute to primary statistics (Requirement 6.5)."""
    return [r for r in records if not r.is_warmup]


def _mean(values: list[float]) -> Optional[float]:
    return (sum(values) / len(values)) if values else None


def _percentile(sorted_values: list[float], p: float) -> float:
    """Linear-interpolation percentile (``p`` in [0,1]) over a pre-sorted list.

    Matches numpy's default / ``statistics.quantiles(method='inclusive')`` for interior points, but
    implemented directly so it runs on any Python (no 3.8+ ``statistics.quantiles`` dependency).
    """
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = p * (len(sorted_values) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(sorted_values) - 1)
    frac = rank - lo
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac


def _quartiles(values: list[float]) -> tuple[Optional[float], Optional[float], Optional[float]]:
    if not values:
        return (None, None, None)
    s = sorted(values)
    median = statistics.median(s)
    return (median, _percentile(s, 0.25), _percentile(s, 0.75))


def _cells(records: list[RunRecord]) -> list[tuple[str, int]]:
    """Distinct (condition, library_size) cells, sorted."""
    return sorted({(r.condition, r.library_size) for r in records})


def aggregate_numeric(records: list[RunRecord], metric: str, field: str) -> list[Aggregate]:
    """Median/IQR/mean of a numeric RunRecord field, per (condition, N)."""
    out = []
    for cond, n in _cells(records):
        cell = [r for r in records if r.condition == cond and r.library_size == n]
        values = [float(getattr(r, field)) for r in cell if getattr(r, field) is not None]
        med, lo, hi = _quartiles(values)
        out.append(Aggregate(
            metric=metric, condition=cond, library_size=n, n=len(values),
            median=med, iqr_low=lo, iqr_high=hi,
            mean=(_mean(values)),
        ))
    return out


def aggregate_latency_cold_warm(records: list[RunRecord]) -> list[Aggregate]:
    """Retrieval latency split into cold (first measured rep) and warm (rest) (Requirement 4.4)."""
    out = []
    for cond, n in _cells(records):
        cell = [r for r in records if r.condition == cond and r.library_size == n
                and r.retrieval_latency_ms is not None]
        cold = [r for r in cell if r.rep == 0]
        warm = [r for r in cell if r.rep != 0]
        for label, group in (("cold", cold), ("warm", warm)):
            values = [r.retrieval_latency_ms for r in group]
            med, lo, hi = _quartiles(values)
            out.append(Aggregate(
                metric=f"retrieval_latency_ms.{label}", condition=cond, library_size=n,
                n=len(values), median=med, iqr_low=lo, iqr_high=hi,
                mean=(_mean(values)),
            ))
    return out


def aggregate_accuracy(records: list[RunRecord], k: int, *, seed: int = 0) -> list[Aggregate]:
    """Model-choice accuracy + recall@K per (condition, N), each with a bootstrap CI."""
    out = []
    for cond, n in _cells(records):
        cell = [r for r in records if r.condition == cond and r.library_size == n]
        mc = score_model_choice(cell, seed=seed)
        out.append(Aggregate(
            metric="model_choice_accuracy", condition=cond, library_size=n, n=mc.n,
            mean=mc.rate, ci_low=mc.ci_low, ci_high=mc.ci_high,
            extra={"successes": mc.successes},
        ))
        rc = score_recall_at_k(cell, k, seed=seed)
        out.append(Aggregate(
            metric=f"recall_at_{k}", condition=cond, library_size=n, n=rc.n,
            mean=rc.rate, ci_low=rc.ci_low, ci_high=rc.ci_high,
            extra={"successes": rc.successes},
        ))
    return out


def aggregate_all(records: list[RunRecord], *, k: int = 5, seed: int = 0) -> list[Aggregate]:
    """Full set of aggregates over the measured (non-warmup) runs."""
    m = measured(records)
    result: list[Aggregate] = []
    result += aggregate_numeric(m, "context_token_cost", "context_token_cost")
    result += aggregate_numeric(m, "retrieval_latency_ms", "retrieval_latency_ms")
    result += aggregate_latency_cold_warm(m)
    result += aggregate_accuracy(m, k, seed=seed)
    return result


def write_aggregates(aggs: list[Aggregate], out_dir: str | Path) -> tuple[Path, Path]:
    """Write aggregates as JSON and a tidy CSV. Deterministic ordering for reproducibility."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    ordered = sorted(aggs, key=lambda a: (a.metric, a.condition, a.library_size))

    json_path = out / "aggregates.json"
    json_path.write_text(json.dumps([asdict(a) for a in ordered], indent=2), encoding="utf-8")

    csv_path = out / "aggregates.csv"
    lines = ["metric,condition,library_size,n,median,iqr_low,iqr_high,mean,ci_low,ci_high"]
    for a in ordered:
        lines.append(",".join("" if v is None else str(v) for v in (
            a.metric, a.condition, a.library_size, a.n, a.median, a.iqr_low, a.iqr_high,
            a.mean, a.ci_low, a.ci_high,
        )))
    csv_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, csv_path


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Aggregate raw RunRecords into per-cell summaries.")
    parser.add_argument("--records", required=True, help="Path to records.jsonl")
    parser.add_argument("--out", required=True, help="Output dir for computed aggregates")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    records = load_records(args.records)
    aggs = aggregate_all(records, k=args.k, seed=args.seed)
    jp, cp = write_aggregates(aggs, args.out)
    print(f"Wrote {jp} and {cp} ({len(aggs)} aggregate rows from {len(records)} records).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
