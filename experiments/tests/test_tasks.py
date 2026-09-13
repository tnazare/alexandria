"""Unit tests for Task_Set loading, completeness validation, and gold-label invariant.

Covers Requirements 5.6 (reject sets missing version/path/labeling) and 9.4/9.7 (gold_skill must be
a Real_Tier slug or null).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from harness.tasks import (
    TaskSet,
    TaskSetError,
    load_task_set,
    validate_against_corpus,
)


def _write_set(tmp_path: Path, data: dict, *, with_labeling: bool = True,
               labeling_name: str = "LABELING.md") -> Path:
    if with_labeling:
        (tmp_path / labeling_name).write_text("# labeling rationale\n", encoding="utf-8")
    set_path = tmp_path / "task_set.json"
    set_path.write_text(json.dumps(data), encoding="utf-8")
    return set_path


def _valid_data() -> dict:
    return {
        "version": "test-v1",
        "labeling_doc": "LABELING.md",
        "tasks": [
            {"id": "t001", "prompt": "extract tables from a pdf", "gold_skill": "pdf"},
            {"id": "t002", "prompt": "rename a variable", "gold_skill": None},
        ],
    }


class TestCompleteness:
    def test_accepts_a_well_formed_set(self, tmp_path):
        path = _write_set(tmp_path, _valid_data())
        ts = load_task_set(path, require_min=False)
        assert isinstance(ts, TaskSet)
        assert ts.version == "test-v1"
        assert len(ts.tasks) == 2
        assert ts.gold_slugs() == {"pdf"}

    def test_rejects_missing_version(self, tmp_path):
        data = _valid_data()
        del data["version"]
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="version"):
            load_task_set(path, require_min=False)

    def test_rejects_blank_version(self, tmp_path):
        data = _valid_data()
        data["version"] = "   "
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="version"):
            load_task_set(path, require_min=False)

    def test_rejects_missing_labeling_doc_field(self, tmp_path):
        data = _valid_data()
        del data["labeling_doc"]
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="labeling_doc"):
            load_task_set(path, require_min=False)

    def test_rejects_labeling_doc_that_does_not_exist(self, tmp_path):
        data = _valid_data()
        # Reference a labeling file we never create on disk.
        data["labeling_doc"] = "MISSING.md"
        path = _write_set(tmp_path, data, with_labeling=False)
        (tmp_path / "LABELING.md").write_text("x", encoding="utf-8")  # a different file exists
        with pytest.raises(TaskSetError, match="does not exist"):
            load_task_set(path, require_min=False)

    def test_rejects_missing_file(self, tmp_path):
        with pytest.raises(TaskSetError, match="not found"):
            load_task_set(tmp_path / "nope.json", require_min=False)

    def test_rejects_empty_tasks(self, tmp_path):
        data = _valid_data()
        data["tasks"] = []
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="non-empty list"):
            load_task_set(path, require_min=False)

    def test_rejects_duplicate_ids(self, tmp_path):
        data = _valid_data()
        data["tasks"][1]["id"] = "t001"
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="Duplicate task ids"):
            load_task_set(path, require_min=False)

    def test_rejects_task_missing_prompt(self, tmp_path):
        data = _valid_data()
        data["tasks"][0]["prompt"] = ""
        path = _write_set(tmp_path, data)
        with pytest.raises(TaskSetError, match="prompt"):
            load_task_set(path, require_min=False)

    def test_enforces_minimum_task_count_when_required(self, tmp_path):
        path = _write_set(tmp_path, _valid_data())
        with pytest.raises(TaskSetError, match="at least 50"):
            load_task_set(path, require_min=True)


class TestGoldLabelInvariant:
    def test_accepts_gold_in_real_slugs(self, tmp_path):
        path = _write_set(tmp_path, _valid_data())
        ts = load_task_set(path, require_min=False)
        # 'pdf' is a real slug; None is always fine.
        validate_against_corpus(ts, real_slugs={"pdf", "xlsx"})

    def test_rejects_gold_not_in_real_slugs(self, tmp_path):
        data = _valid_data()
        data["tasks"][0]["gold_skill"] = "pdf-processing-rust-lopdf"  # a padding variant
        path = _write_set(tmp_path, data)
        ts = load_task_set(path, require_min=False)
        with pytest.raises(TaskSetError, match="not a Real_Tier slug"):
            validate_against_corpus(ts, real_slugs={"pdf", "xlsx"})

    def test_null_gold_is_always_valid(self, tmp_path):
        data = _valid_data()
        for t in data["tasks"]:
            t["gold_skill"] = None
        path = _write_set(tmp_path, data)
        ts = load_task_set(path, require_min=False)
        validate_against_corpus(ts, real_slugs=set())  # no reals needed; all null


def test_ships_skeleton_set_validates():
    """The committed skeleton set loads (with the relaxed minimum) and its gold labels parse."""
    root = Path(__file__).resolve().parents[1]
    ts = load_task_set(root / "tasks" / "task_set.v1.json", require_min=False)
    assert ts.version
    # every gold is a slug or None
    for t in ts.tasks:
        assert t.gold_skill is None or isinstance(t.gold_skill, str)
