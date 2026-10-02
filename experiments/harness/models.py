"""Core data models for the skill-retrieval-benchmark harness.

These dataclasses are the shared vocabulary across the harness: the corpus tiers, one benchmark
task, and the atomic unit of measurement (``RunRecord``). Everything is JSON-serializable so raw
results can be persisted as JSON lines and recomputed later (Requirement 7.4).
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional


class Tier(str, Enum):
    """Corpus tier a skill belongs to (Requirement 9.1)."""

    REAL = "real"          # genuine harvested skills; the only tier eligible to be a Gold_Skill
    DISTRACTOR = "distractor"  # realistic but task-wrong skills; never a Gold_Skill
    PADDING = "padding"    # generated variant skills, only to scale Library_Size; never a Gold_Skill


class Condition(str, Enum):
    """A retrieval mechanism under test."""

    ALEXANDRIA = "alexandria"  # server-side MCP: resolve_skill + fetch_skill
    NATIVE = "native"          # client-side .claude/skills/ (preload + progressive disclosure)
    CATALOG = "catalog"        # resident compact catalog in prompt + single on-demand fetch_skill(id)


@dataclass(frozen=True)
class HarvestedSkill:
    """A genuine skill collected by the Harvester, with provenance (Requirement 9.2, 9.8)."""

    slug: str
    name: str
    description: str
    body: str                 # full SKILL.md content
    tier: Tier                # REAL or DISTRACTOR
    source_url: str
    ref: str                  # pinned commit/tag
    license: str              # SPDX id or "UNKNOWN"
    sha256: str


@dataclass(frozen=True)
class GeneratedSkill:
    """A deterministic variant skill for the Padding_Tier (Requirement 9.5)."""

    slug: str                 # e.g. "pdf-processing-rust-lopdf"
    name: str
    description: str
    body: str
    concept: str              # e.g. "pdf-processing"
    language: str             # e.g. "rust"
    framework: str            # e.g. "lopdf"
    sha256: str


@dataclass(frozen=True)
class ManifestEntry:
    """One skill in a built corpus manifest (Requirement 1.3, 9.8)."""

    slug: str
    tier: Tier
    name: str
    sha256: str
    source_url: Optional[str] = None
    ref: Optional[str] = None
    license: Optional[str] = None


@dataclass(frozen=True)
class CorpusManifest:
    """The frozen description of a size-N corpus, driving both conditions (Requirement 1.1, 1.3)."""

    experiment_version: str
    library_size: int
    seed: int
    content_hash: str         # sha256 over sorted (slug, sha256) pairs
    skills: list[ManifestEntry]

    def slugs(self) -> list[str]:
        return [s.slug for s in self.skills]

    def real_slugs(self) -> set[str]:
        return {s.slug for s in self.skills if s.tier == Tier.REAL}


@dataclass(frozen=True)
class Task:
    """One benchmark item: a prompt plus its gold label (Requirement 5.1, 9.7)."""

    id: str
    prompt: str
    gold_skill: Optional[str]   # Real_Tier slug, or None for "no skill applies"
    rationale: str = ""
    distractor_notes: str = ""


@dataclass
class RunRecord:
    """The atomic unit of measurement: one (task, condition, size, rep) execution.

    Raw ``RunRecord`` lines are the source of truth; all aggregates recompute from them
    (Requirement 7.4). Fields default to None so a partially-captured run is still recordable.
    """

    run_id: str
    experiment_version: str
    condition: str                       # Condition value
    library_size: int
    content_hash: str
    task_id: str
    gold_skill: Optional[str]
    rep: int
    is_warmup: bool = False

    # accuracy signals
    chosen_skill: Optional[str] = None            # slug the agent actually used (model-choice)
    resolve_candidates: Optional[list[str]] = None  # slugs surfaced (recall@k)
    consulted: Optional[bool] = None              # did the agent consult its skill library at all
                                                  # (resolve_skill called / Skill invoked) — compliance

    # latency (milliseconds)
    retrieval_latency_ms: Optional[float] = None
    end_to_end_latency_ms: Optional[float] = None
    native_preload_ms: Optional[float] = None      # one-time startup cost, native only

    # token cost
    context_token_cost: Optional[int] = None       # offline, fixed tokenizer (primary)
    reported_usage: Optional[dict[str, Any]] = None  # claude usage block (corroboration)

    # provenance
    model_id: Optional[str] = None
    model_version: Optional[str] = None
    seed: Optional[int] = None
    timestamp: Optional[str] = None
    notes: str = ""

    def to_json(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> "RunRecord":
        fields = {f.name for f in dataclasses.fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in fields})


@dataclass(frozen=True)
class Aggregate:
    """A computed summary for one (metric, condition, library_size) cell (Requirement 6.2)."""

    metric: str               # "context_token_cost" | "retrieval_latency_ms" | "model_choice_accuracy" | ...
    condition: str
    library_size: int
    n: int                    # number of measured (non-warmup) runs
    median: Optional[float] = None
    iqr_low: Optional[float] = None   # 25th percentile
    iqr_high: Optional[float] = None  # 75th percentile
    mean: Optional[float] = None
    ci_low: Optional[float] = None    # bootstrap CI (accuracy)
    ci_high: Optional[float] = None
    extra: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        return dataclasses.asdict(self)
