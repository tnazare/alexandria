"""Task_Set loading and completeness validation (Requirements 5.1, 5.6, 9.7, 9.9).

A Task_Set is a versioned JSON file of labeled benchmark items. Before any run, the harness rejects
a set that is missing any required component — a ``version``, a stored path, or a companion labeling
document (Requirement 5.6). It also enforces the gold-label invariant: every ``gold_skill`` must be
``null`` (no-skill-applies control) or a slug that exists in the Real_Tier of the corpus
(Requirement 9.4, 9.7). The corpus check is applied when the corpus is known.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from harness.models import Task

# Minimum number of labeled tasks for the first pass (Requirement 9.9).
MIN_TASKS = 50


class TaskSetError(ValueError):
    """Raised when a Task_Set is malformed or fails a completeness/label invariant."""


@dataclass(frozen=True)
class TaskSet:
    """A validated, versioned collection of Tasks."""

    version: str
    labeling_doc: str          # path (relative to the set file's dir) to the labeling rationale
    source_path: str           # where this set was loaded from
    tasks: list[Task]

    def gold_slugs(self) -> set[str]:
        return {t.gold_skill for t in self.tasks if t.gold_skill is not None}


def load_task_set(path: str | Path, *, min_tasks: int = MIN_TASKS,
                  require_min: bool = True) -> TaskSet:
    """Load and validate a Task_Set file.

    Rejects (halts, per Requirement 5.6) a set that lacks a version, a stored location, or a
    labeling document, or whose tasks are malformed. Does not check gold slugs against a corpus here
    — call ``validate_against_corpus`` once the corpus manifest is known.

    :param require_min: enforce the >=50-task minimum (Requirement 9.9). Skeleton/tests may relax it.
    """
    p = Path(path)
    if not p.is_file():
        raise TaskSetError(f"Task_Set file not found: {p}")

    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise TaskSetError(f"Task_Set is not valid JSON: {e}") from e

    if not isinstance(data, dict):
        raise TaskSetError("Task_Set must be a JSON object.")

    # --- required components (Requirement 5.6) ---
    version = data.get("version")
    if not version or not str(version).strip():
        raise TaskSetError("Task_Set is missing a non-empty 'version'.")

    labeling_doc = data.get("labeling_doc")
    if not labeling_doc or not str(labeling_doc).strip():
        raise TaskSetError("Task_Set is missing 'labeling_doc' (the labeling rationale).")

    # The labeling document must actually exist on disk, resolved relative to the set file.
    labeling_path = (p.parent / labeling_doc).resolve()
    if not labeling_path.is_file():
        raise TaskSetError(f"Task_Set 'labeling_doc' does not exist: {labeling_doc}")

    raw_tasks = data.get("tasks")
    if not isinstance(raw_tasks, list) or not raw_tasks:
        raise TaskSetError("Task_Set 'tasks' must be a non-empty list.")

    tasks = [_parse_task(i, t) for i, t in enumerate(raw_tasks)]
    _check_unique_ids(tasks)

    if require_min and len(tasks) < min_tasks:
        raise TaskSetError(
            f"Task_Set has {len(tasks)} tasks; at least {min_tasks} are required (Requirement 9.9)."
        )

    return TaskSet(
        version=str(version),
        labeling_doc=str(labeling_doc),
        source_path=str(p),
        tasks=tasks,
    )


def validate_against_corpus(task_set: TaskSet, real_slugs: set[str]) -> None:
    """Enforce that every gold_skill is null or an existing Real_Tier slug (Requirement 9.4, 9.7).

    Raises TaskSetError listing any offending task ids. Called once the corpus manifest is built.
    """
    offenders = sorted(
        t.id for t in task_set.tasks
        if t.gold_skill is not None and t.gold_skill not in real_slugs
    )
    if offenders:
        raise TaskSetError(
            "These tasks have a gold_skill that is not a Real_Tier slug "
            f"(padding/distractor/unknown are not valid gold labels): {offenders}"
        )


def _parse_task(index: int, raw: object) -> Task:
    if not isinstance(raw, dict):
        raise TaskSetError(f"Task at index {index} is not an object.")

    task_id = raw.get("id")
    if not task_id or not str(task_id).strip():
        raise TaskSetError(f"Task at index {index} is missing an 'id'.")

    prompt = raw.get("prompt")
    if not prompt or not str(prompt).strip():
        raise TaskSetError(f"Task '{task_id}' is missing a non-empty 'prompt'.")

    # gold_skill is optional in JSON; absent and explicit null both mean "no skill applies".
    gold = raw.get("gold_skill", None)
    if gold is not None and (not isinstance(gold, str) or not gold.strip()):
        raise TaskSetError(f"Task '{task_id}' has an invalid 'gold_skill' (must be a slug or null).")

    return Task(
        id=str(task_id),
        prompt=str(prompt),
        gold_skill=gold,
        rationale=str(raw.get("rationale", "")),
        distractor_notes=str(raw.get("distractor_notes", "")),
    )


def _check_unique_ids(tasks: list[Task]) -> None:
    seen: set[str] = set()
    dupes: set[str] = set()
    for t in tasks:
        if t.id in seen:
            dupes.add(t.id)
        seen.add(t.id)
    if dupes:
        raise TaskSetError(f"Duplicate task ids: {sorted(dupes)}")
