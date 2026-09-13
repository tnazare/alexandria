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

## Task mix (v1: 54 tasks)

- **Clear-match (38 tasks, t001–t038)** — the prompt plainly calls for one real skill. Every one of
  the 19 real skills is the gold answer for at least one task; the higher-traffic document skills
  (`pdf`, `xlsx`, `docx`, `pptx`) and a few others have two clear-match tasks each.
- **Near-variant trap (9 tasks, t039–t047)** — the prompt is close to a Padding_Tier variant's
  domain (e.g. `pdf-processing-python-stdlib`), but the gold is the real canonical skill. Selecting
  the variant is scored **wrong**. These make accuracy non-trivial and validate exact-slug pinning.
- **No-skill-applies (7 tasks, t048–t054)** — trivial edits, general knowledge, or off-domain asks;
  gold is `null`. A correct system invokes no skill. Guards against a system that always fires.

Each task carries a `category` field (for reporting) and a `rationale`; trap tasks also carry
`distractor_notes` naming the variant(s) that must not be accepted.

## Gold-label coverage

All 19 real slugs appear as a gold label: `academy-guide`, `algorithmic-art`, `brand-guidelines`,
`canvas-design`, `claude-api`, `discernment-nudge`, `doc-coauthoring`, `docx`, `frontend-design`,
`internal-comms`, `mcp-builder`, `pdf`, `pptx`, `skill-creator`, `slack-gif-creator`, `theme-factory`,
`web-artifacts-builder`, `webapp-testing`, `xlsx`. No gold label is a padding or distractor slug
(enforced by `validate_against_corpus`).

## Versioning

The set is frozen at `version: v1`. Changing labels or tasks requires a new version so results
remain comparable within a version.
