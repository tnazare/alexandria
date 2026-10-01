"""Unit tests for token measurement, scoring, and aggregation.

All use an injected trivial token counter (len of whitespace-split words) so no ``tiktoken`` runtime
is needed. Covers Requirements 3.1-3.3 (token purity + mechanism shape), 5.2/5.3 (scoring incl.
no-skill-applies), 6.2/6.5 (aggregation, warm-up exclusion), 7.4 (re-aggregation identical).
"""

from __future__ import annotations

import json
from pathlib import Path

from harness.aggregate import (
    aggregate_all,
    load_records,
    measured,
    write_aggregates,
)
from harness.build_corpus import compute_content_hash
from harness.measure_tokens import (
    alexandria_resident_tokens,
    alexandria_task_tokens,
    native_resident_tokens,
    native_task_tokens,
)
from harness.models import CorpusManifest, Condition, ManifestEntry, RunRecord, Tier
from harness.score import is_model_choice_correct, is_recall_hit, score_model_choice


# Trivial deterministic counter: number of whitespace-delimited tokens.
def wc(text: str) -> int:
    return len(text.split())


def _skill_body(slug: str, desc_words: int = 5, body_words: int = 40) -> str:
    desc = " ".join(["d"] * desc_words)
    body_line = " ".join(["b"] * body_words)
    return f"---\nname: {slug}\ndescription: {desc}\n---\n# {slug}\n{body_line}\n"


def _write_corpus(tmp_path: Path, slugs: list[str]):
    """Materialize a real-only corpus (all Tier.REAL) + manifest, like a subset materialization."""
    corpus = tmp_path / "corpus"
    entries = []
    for slug in slugs:
        d = corpus / Tier.REAL.value / slug
        d.mkdir(parents=True, exist_ok=True)
        (d / "SKILL.md").write_text(_skill_body(slug), encoding="utf-8")
        entries.append(ManifestEntry(slug=slug, tier=Tier.REAL, name=slug, sha256=f"s-{slug}"))
    manifest = CorpusManifest(experiment_version="v1", library_size=len(entries), seed=1,
                              content_hash=compute_content_hash(entries), skills=entries)
    return manifest, corpus


class TestTokenMechanismShape:
    def test_alexandria_resident_is_constant_in_n(self):
        # Alexandria resident cost is just the two tool schemas; independent of any corpus.
        assert alexandria_resident_tokens(wc) == alexandria_resident_tokens(wc)
        assert alexandria_resident_tokens(wc) > 0

    def test_native_resident_grows_with_n(self, tmp_path):
        m_small, c_small = _write_corpus(tmp_path / "a", [f"r{i}" for i in range(10)])
        m_big, c_big = _write_corpus(tmp_path / "b", [f"r{i}" for i in range(40)])
        small = native_resident_tokens(wc, m_small, c_small)
        big = native_resident_tokens(wc, m_big, c_big)
        # More skills resident -> strictly more resident tokens (frontmatter summed over N).
        assert big > small

    def test_alexandria_task_adds_resolve_and_body(self):
        bd = alexandria_task_tokens(wc, resolve_result_text="a b c", fetched_body="x y")
        assert bd.per_task == wc("a b c") + wc("x y")
        assert bd.resident == alexandria_resident_tokens(wc)
        assert bd.total == bd.resident + bd.per_task

    def test_native_task_adds_only_matched_body(self, tmp_path):
        manifest, corpus = _write_corpus(tmp_path, ["pdf", "xlsx"])
        matched = native_task_tokens(wc, manifest, corpus, matched_slug="pdf")
        none = native_task_tokens(wc, manifest, corpus, matched_slug=None)
        assert none.per_task == 0                 # no disclosure when no skill applies
        assert matched.per_task > 0               # one body disclosed
        assert matched.resident == none.resident  # resident preload identical


class TestScoring:
    def _rec(self, gold, chosen, cand=None, warmup=False, rep=0, cond="alexandria", n=20):
        return RunRecord(run_id="r", experiment_version="v1", condition=cond, library_size=n,
                         content_hash="h", task_id="t", gold_skill=gold, rep=rep,
                         is_warmup=warmup, chosen_skill=chosen, resolve_candidates=cand)

    def test_model_choice_correct_and_wrong(self):
        assert is_model_choice_correct(self._rec("pdf", "pdf")) is True
        assert is_model_choice_correct(self._rec("pdf", "xlsx")) is False

    def test_no_skill_applies_control(self):
        assert is_model_choice_correct(self._rec(None, None)) is True    # correctly used none
        assert is_model_choice_correct(self._rec(None, "pdf")) is False  # fired a skill wrongly

    def test_recall_hit_and_miss(self):
        assert is_recall_hit(self._rec("pdf", None, cand=["xlsx", "pdf", "docx"]), k=5) is True
        assert is_recall_hit(self._rec("pdf", None, cand=["xlsx", "docx"]), k=5) is False

    def test_recall_respects_k(self):
        assert is_recall_hit(self._rec("pdf", None, cand=["a", "b", "pdf"]), k=2) is False
        assert is_recall_hit(self._rec("pdf", None, cand=["a", "b", "pdf"]), k=3) is True

    def test_recall_na_for_no_gold_or_no_candidates(self):
        assert is_recall_hit(self._rec(None, None, cand=["pdf"]), k=5) is None
        assert is_recall_hit(self._rec("pdf", None, cand=None), k=5) is None

    def test_score_rate_and_ci_deterministic(self):
        recs = [self._rec("pdf", "pdf") for _ in range(8)] + [self._rec("pdf", "xlsx") for _ in range(2)]
        r1 = score_model_choice(recs, seed=42)
        r2 = score_model_choice(recs, seed=42)
        assert r1.rate == 0.8
        assert (r1.ci_low, r1.ci_high) == (r2.ci_low, r2.ci_high)  # deterministic bootstrap


class TestAggregation:
    def _mkrecords(self):
        recs = []
        for rep in range(3):
            recs.append(RunRecord(run_id="r", experiment_version="v1", condition="alexandria",
                                  library_size=20, content_hash="h", task_id=f"t{rep}",
                                  gold_skill="pdf", rep=rep, is_warmup=(rep == 0),
                                  chosen_skill="pdf", resolve_candidates=["pdf"],
                                  retrieval_latency_ms=10.0 + rep, context_token_cost=100 + rep))
        return recs

    def test_warmup_excluded_from_measured(self):
        recs = self._mkrecords()
        assert len(recs) == 3
        assert len(measured(recs)) == 2  # rep 0 is warmup

    def test_aggregate_all_produces_rows(self):
        aggs = aggregate_all(self._mkrecords(), k=5, seed=0)
        metrics = {a.metric for a in aggs}
        assert "context_token_cost" in metrics
        assert "retrieval_latency_ms" in metrics
        assert "model_choice_accuracy" in metrics
        assert "recall_at_5" in metrics

    def test_reaggregation_is_byte_identical(self, tmp_path):
        # Write records.jsonl, aggregate twice, compare outputs byte-for-byte (Requirement 7.4).
        records_path = tmp_path / "records.jsonl"
        with records_path.open("w", encoding="utf-8") as f:
            for r in self._mkrecords():
                f.write(json.dumps(r.to_json()) + "\n")

        recs = load_records(records_path)
        out1 = tmp_path / "c1"
        out2 = tmp_path / "c2"
        write_aggregates(aggregate_all(recs, k=5, seed=0), out1)
        write_aggregates(aggregate_all(recs, k=5, seed=0), out2)
        assert (out1 / "aggregates.json").read_bytes() == (out2 / "aggregates.json").read_bytes()
        assert (out1 / "aggregates.csv").read_bytes() == (out2 / "aggregates.csv").read_bytes()
