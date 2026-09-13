# Task_Set Labeling Rationale

This document explains how the benchmark tasks in `task_set.v1.json` were written and labeled, so the
gold labels can be audited and reused (Requirement 5.6).

## What a task is

Each task is a single natural-language coding request plus a **gold label** — the one skill a correct
system should surface/select for it (`gold_skill`), or `null` when **no skill should apply**.

## Labeling rules

1. **Gold skills are Real_Tier only.** A task's `gold_skill` must be the stable slug of a genuine
   harvested skill (Real_Tier). A padding variant or distractor is never a valid gold label; if a
   system selects one instead of the gold, it is scored **wrong** (Requirement 9.4, 9.7).
2. **One gold per task.** Tasks are written so exactly one Real_Tier skill is the intended match, to
   keep model-choice accuracy unambiguous. Tasks that could legitimately match two real skills are
   rewritten or dropped.
3. **Exact-slug pinning.** Because the Padding_Tier contains realistic *variants* (same concept,
   different language/framework), the prompt and gold label are specific enough to distinguish, e.g.,
   the canonical `pdf` skill from a `pdf-processing-rust-lopdf` padding variant.
4. **No-skill-applies controls.** A subset of tasks are deliberately trivial or out-of-scope
   (`gold_skill: null`); a correct system invokes **no** skill. These guard against a system that
   always fires something.

## Task mix (target)

- **Clear-match** — the prompt plainly calls for one real skill (e.g. "extract tables from a PDF").
- **Near-variant trap** — the prompt is close to a padding variant's domain but the gold is the real
  canonical skill; selecting the variant is wrong.
- **No-skill-applies** — trivial edits / out-of-domain asks; gold is `null`.

## Versioning

The set is frozen per experiment version (`version` field). Changing labels or tasks requires a new
version so results remain comparable within a version.

> This is a skeleton for the v1 set. The full ≥50-task set is authored in task 9; this file is
> completed there with the final per-category counts and any per-task notes.
