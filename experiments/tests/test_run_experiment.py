"""Unit tests for the orchestrator's pure building blocks (Requirements 6.3, 6.4, 7.6).

Covers env capture (model id/version together-or-neither), seeded task ordering determinism, and the
resolver-identity -> slug maps. The live matrix executor is covered by the opt-in smoke test.
"""

from __future__ import annotations

from harness.config import ExperimentConfig
from harness.models import CorpusManifest, ManifestEntry, Tier
from harness.run_experiment import (
    build_env,
    build_id_to_slug,
    native_name_to_slug,
    ordered_tasks,
)


def _cfg() -> ExperimentConfig:
    return ExperimentConfig(raw={
        "experiment_version": "v1",
        "library_sizes": [20, 100],
        "nrep": 5, "warmup": 1, "k": 5,
        "seeds": {"padding": 1234, "order": 5678},
        "conditions": ["alexandria", "native"],
        "tokenizer_encoding": "cl100k_base",
    })


def _manifest() -> CorpusManifest:
    return CorpusManifest(
        experiment_version="v1", library_size=3, seed=1, content_hash="sha256:x",
        skills=[
            ManifestEntry(slug="pdf", tier=Tier.REAL, name="Pdf", sha256="a"),
            ManifestEntry(slug="xlsx", tier=Tier.REAL, name="Xlsx", sha256="b"),
            ManifestEntry(slug="pdf-processing-rust-actix", tier=Tier.PADDING,
                          name="Pdf Processing (Rust/Actix)", sha256="c"),
        ],
    )


class TestEnv:
    def test_records_model_id_and_version_together(self):
        env = build_env(_cfg(), model_id="claude-x", model_version="2026-01")
        assert env["model"] == {"model_id": "claude-x", "model_version": "2026-01"}

    def test_flags_missing_provenance_when_no_id(self):
        env = build_env(_cfg(), model_id=None, model_version="ignored-because-no-id")
        assert env["model"]["model_id"] is None
        assert env["model"]["model_version"] is None
        assert env["model"]["missing_model_provenance"] is True

    def test_captures_seeds_and_config(self):
        env = build_env(_cfg(), model_id="m", model_version=None)
        assert env["seeds"] == {"padding": 1234, "order": 5678}
        assert env["library_sizes"] == [20, 100]
        assert env["nrep"] == 5 and env["warmup"] == 1 and env["k"] == 5
        assert env["tokenizer_encoding"] == "cl100k_base"


class TestOrdering:
    def test_seeded_order_is_deterministic(self):
        ids = [f"t{i}" for i in range(30)]
        assert ordered_tasks(ids, seed=5678) == ordered_tasks(ids, seed=5678)

    def test_different_seed_reorders(self):
        ids = [f"t{i}" for i in range(30)]
        assert ordered_tasks(ids, seed=1) != ordered_tasks(ids, seed=2)

    def test_is_a_permutation(self):
        ids = [f"t{i}" for i in range(30)]
        assert sorted(ordered_tasks(ids, seed=5678)) == sorted(ids)


class TestIdMaps:
    def test_build_id_to_slug_covers_name_and_slug(self):
        m = _manifest()
        name_of = {e.slug: e.name for e in m.skills}
        id_to_slug = build_id_to_slug(m, name_for_slug=lambda s: name_of[s])
        # slug identity
        assert id_to_slug["pdf"] == "pdf"
        # display name -> slug
        assert id_to_slug["Pdf"] == "pdf"
        assert id_to_slug["Pdf Processing (Rust/Actix)"] == "pdf-processing-rust-actix"

    def test_native_name_to_slug(self):
        m = _manifest()
        n2s = native_name_to_slug(m)
        assert n2s["Xlsx"] == "xlsx"
        assert n2s["xlsx"] == "xlsx"
