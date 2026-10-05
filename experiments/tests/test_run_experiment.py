"""Unit tests for the orchestrator's pure building blocks (Requirements 6.3, 6.4, 7.6).

Covers env capture (model id/version together-or-neither), seeded task ordering determinism, and the
resolver-identity -> slug maps. The live matrix executor is covered by the opt-in smoke test.
"""

from __future__ import annotations

import pytest

from harness.config import ExperimentConfig
from harness.models import CorpusManifest, ManifestEntry, Tier
from harness.run_experiment import (
    SearchModeMismatchError,
    actuator_info_url,
    build_env,
    build_id_to_slug,
    native_name_to_slug,
    ordered_tasks,
    verify_search_mode,
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
        assert env["model"]["model_id"] == "claude-x"
        assert env["model"]["model_version"] == "2026-01"
        assert "missing_model_provenance" not in env["model"]

    def test_flags_missing_provenance_when_no_id(self):
        env = build_env(_cfg(), model_id=None, model_version="ignored-because-no-id")
        assert env["model"]["model_id"] is None
        assert env["model"]["model_version"] is None
        assert env["model"]["missing_model_provenance"] is True

    def test_records_effort(self):
        cfg = _cfg()
        cfg.raw["effort"] = "high"
        env = build_env(cfg, model_id="claude-x", model_version="2026-01")
        assert env["model"]["effort"] == "high"

    def test_captures_seeds_and_config(self):
        env = build_env(_cfg(), model_id="m", model_version=None)
        assert env["seeds"] == {"padding": 1234, "order": 5678}
        assert env["library_sizes"] == [20, 100]
        assert env["nrep"] == 5 and env["warmup"] == 1 and env["k"] == 5
        assert env["tokenizer_encoding"] == "cl100k_base"


class TestSearchMode:
    def test_env_carries_selected_mode(self):
        # The selected mode flows into env.json via the `extra` block (Requirement 7.1).
        env = build_env(_cfg(), model_id="m", model_version=None,
                        extra={"subset": "close-semantics-20", "search_mode": "semantic"})
        assert env["search_mode"] == "semantic"

    def test_env_mode_is_none_when_not_selected(self):
        env = build_env(_cfg(), model_id="m", model_version=None,
                        extra={"subset": "close-semantics-20", "search_mode": None})
        assert env["search_mode"] is None

    def test_actuator_url_derived_from_host(self):
        assert actuator_info_url("http://localhost:8080") == "http://localhost:8080/actuator/info"
        # trailing slash on host is normalized
        assert actuator_info_url("http://localhost:8080/") == "http://localhost:8080/actuator/info"

    def test_verify_passes_when_modes_match(self):
        # Backend reports the same mode the harness was told → returns the verified mode, no raise.
        verified = verify_search_mode("semantic", "http://backend",
                                      fetch=lambda host: "semantic")
        assert verified == "semantic"

    def test_verify_aborts_on_mismatch(self):
        # Backend reports a different mode → the run aborts so it is never mislabeled (Requirement 7.1).
        with pytest.raises(SearchModeMismatchError) as exc:
            verify_search_mode("semantic", "http://backend", fetch=lambda host: "fts")
        msg = str(exc.value)
        assert "semantic" in msg and "fts" in msg  # clear error names both sides

    def test_verify_skipped_when_no_mode_selected(self):
        # No --search-mode → verification is skipped (native-only or opt-out); fetch not called.
        def _boom(host):
            raise AssertionError("fetch must not be called when expected is None")
        assert verify_search_mode(None, "http://backend", fetch=_boom) is None

    def test_verify_aborts_when_backend_reports_no_mode(self):
        # Older/unexpected backend that doesn't surface $.search.mode cannot be verified.
        with pytest.raises(SearchModeMismatchError):
            verify_search_mode("hybrid", "http://backend", fetch=lambda host: None)

    def test_verify_aborts_when_backend_unreachable(self):
        import urllib.error

        def _down(host):
            raise urllib.error.URLError("connection refused")
        with pytest.raises(SearchModeMismatchError):
            verify_search_mode("fts", "http://backend", fetch=_down)

    def test_fetch_reads_search_mode_from_actuator_info(self, monkeypatch):
        # Mock the HTTP GET so we parse $.search.mode without a live backend.
        import io
        import json
        import urllib.request
        from harness import run_experiment as rx

        class _Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                self.close()

        captured = {}

        def _fake_urlopen(req, timeout=None):
            captured["url"] = req.full_url
            payload = {"search": {"mode": "hybrid", "embeddingProvider": "local",
                                  "embeddingDim": 256}}
            return _Resp(json.dumps(payload).encode("utf-8"))

        monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen)
        mode = rx.fetch_backend_search_mode("http://localhost:8080")
        assert mode == "hybrid"
        assert captured["url"] == "http://localhost:8080/actuator/info"

    def test_fetch_returns_none_when_search_block_absent(self, monkeypatch):
        import io
        import json
        import urllib.request
        from harness import run_experiment as rx

        class _Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                self.close()

        def _fake_urlopen(req, timeout=None):
            return _Resp(json.dumps({"app": {"name": "alexandria"}}).encode("utf-8"))

        monkeypatch.setattr(urllib.request, "urlopen", _fake_urlopen)
        assert rx.fetch_backend_search_mode("http://localhost:8080") is None


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


class TestResume:
    def test_cell_key_is_stable(self):
        from harness.run_experiment import cell_key
        assert cell_key("native", 20, "t001", 0) == cell_key("native", "20", "t001", 0)
        assert cell_key("native", 20, "t001", 0) != cell_key("alexandria", 20, "t001", 0)

    def test_completed_cells_reads_recorded_keys(self, tmp_path):
        import json
        from harness.run_experiment import cell_key, completed_cells
        run_dir = tmp_path / "run-x"
        run_dir.mkdir()
        rows = [
            {"condition": "alexandria", "library_size": 20, "task_id": "t001", "rep": 0},
            {"condition": "native", "library_size": 20, "task_id": "t002", "rep": 1},
        ]
        (run_dir / "records.jsonl").write_text(
            "\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
        done = completed_cells(run_dir)
        assert cell_key("alexandria", 20, "t001", 0) in done
        assert cell_key("native", 20, "t002", 1) in done
        assert cell_key("native", 20, "t999", 0) not in done

    def test_completed_cells_empty_when_no_file(self, tmp_path):
        from harness.run_experiment import completed_cells
        assert completed_cells(tmp_path / "nope") == set()

    def test_completed_cells_tolerates_bad_lines(self, tmp_path):
        from harness.run_experiment import cell_key, completed_cells
        run_dir = tmp_path / "run-y"
        run_dir.mkdir()
        (run_dir / "records.jsonl").write_text(
            '{"condition":"native","library_size":100,"task_id":"t001","rep":0}\n'
            "not json\n\n", encoding="utf-8")
        done = completed_cells(run_dir)
        assert cell_key("native", 100, "t001", 0) in done
        assert len(done) == 1
