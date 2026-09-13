# Implementation Plan: Skill Retrieval Benchmark

## Overview

This plan builds a reproducible experiment comparing **Alexandria** (server-side MCP skill library)
against **native** `.claude/skills/` on **token cost**, **retrieval latency**, and **accuracy**, for
**Claude Code** in the first pass. Everything lives under a top-level `experiments/` folder and
touches **no** Alexandria product code (Requirement 7.1, 7.2).

Each numbered subtask is a self-contained commit. Tasks marked `*` are tests (pytest for the
deterministic parts) that can trail the implementation slightly but must land before the milestone
closes. Every task cites the requirements it satisfies.

Language/tooling: Python with pinned deps (`tiktoken`, `httpx`, `pyyaml`, `pytest`). Reuse the proven
frontmatter parser from `scripts/import_anthropic_skills.py` and the documented `client_secret=any`
OIDC + importer flow. The LLM itself is non-deterministic; everything else is seeded and
recomputable from raw records.

Run/verify: `pytest experiments/` (deterministic units); `python -m harness.run_experiment ...`
(needs the running `docker compose` stack + Claude Code CLI) for smoke and full runs.

## Tasks

- [x] 1. Scaffold `experiments/` and pin the environment
  - [x] 1.1 Create the folder skeleton, pinned deps, and config
    - Create `experiments/` with `README.md`, `pyproject.toml`/`requirements.txt` (pinned `tiktoken`,
      `httpx`, `pyyaml`, `pytest`), and `config/experiment.yaml` (sizes `[20,100,500]`, `nrep`, `K`,
      seeds, model id, host, warmup count).
    - *Files:* `experiments/README.md`, `experiments/pyproject.toml`, `experiments/config/experiment.yaml`
    - *Requirements: 7.1, 7.3, 7.6*

  - [x] 1.2 Core data models and the fixed tokenizer
    - `harness/models.py`: `Task`, `HarvestedSkill`, `GeneratedSkill`, `RunRecord`, `Aggregate`
      dataclasses (fields per design §5).
    - `harness/tokenizer.py`: single pinned-encoding tokenizer wrapper used by both conditions.
    - *Files:* `experiments/harness/models.py`, `experiments/harness/tokenizer.py`
    - *Requirements: 3.1, 7.3*

- [x] 2. Task set + validation
  - [x] 2.1 Task-set schema, loader, and completeness validation
    - Define `tasks/task_set.v1.json` schema (id, prompt, gold_skill|null, rationale) and a loader
      that rejects a set missing `version`, stored path, or `LABELING.md` (halt before any run).
    - Stub `tasks/LABELING.md` describing the labeling rationale.
    - *Files:* `experiments/harness/tasks.py`, `experiments/tasks/LABELING.md`,
      `experiments/tasks/task_set.v1.json` (skeleton)
    - *Requirements: 5.1, 5.6, 9.9*

  - [x]* 2.2 Unit test task-set validation
    - Reject sets missing version/path/labeling; accept a well-formed set; assert every `gold_skill`
      is either `null` or a Real_Tier slug (enforced once the corpus exists).
    - *Requirements: 5.6, 9.4*

- [ ] 3. Harvester (Real_Tier + Distractor_Tier) with provenance
  - [ ] 3.1 `sources.yaml` + harvester
    - `harness/harvest.py`: walk pinned GitHub sources (start with `anthropics/skills`), take every
      `**/SKILL.md`, parse frontmatter (reuse importer logic), record `source_url`/`ref`/`license`/
      `sha256`, skip-and-log malformed, de-dupe by `(name, sha256)`. Write `corpus/real/<slug>/` and
      `corpus/distractor/<slug>/` plus manifest rows; tier assignment from `sources.yaml`.
    - *Files:* `experiments/harness/harvest.py`, `experiments/config/sources.yaml`
    - *Requirements: 9.2, 9.3, 9.8*

  - [ ]* 3.2 Unit test frontmatter parity + skip-on-malformed
    - Parser matches the importer on block-scalar descriptions; a malformed SKILL.md is skipped and
      logged, never included.
    - *Requirements: 9.3*

- [ ] 4. Padding generator + corpus builder
  - [ ] 4.1 Seeded variant-skill generator
    - `harness/generate_padding.py`: deterministic cartesian walk over CONCEPTS × STACKS producing
      realistic variant skills (`<concept>-<lang>-<framework>` slugs, templated bodies of realistic
      size), skipping any reserved (real/distractor) slug; seeded so `(target_n, seed)` reproduces.
    - *Files:* `experiments/harness/generate_padding.py`
    - *Requirements: 2.2, 2.4, 9.4, 9.5*

  - [ ] 4.2 Corpus builder + manifest/hashing
    - `harness/build_corpus.py`: assemble size-N corpus = all real + all distractor + enough padding;
      write files + `corpus/manifest.json` (tier, slug, source, license, sha256) and a `content_hash`
      over sorted `(slug, sha256)`. Halt if any skill fails to materialize.
    - *Files:* `experiments/harness/build_corpus.py`
    - *Requirements: 1.1, 1.3, 9.1, 9.6, 1.4*

  - [ ]* 4.3 Unit tests: determinism, no-collision, stable hash
    - `generate_padding(N, seed)` stable and never collides with reserved slugs; `build_corpus`
      yields a stable `content_hash` and identical tier sets for both conditions.
    - *Requirements: 2.4, 9.4, 9.5, 1.1*

- [ ] 5. Provisioning (both conditions) with halting guards
  - [ ] 5.1 Alexandria provisioning
    - `harness/provision_alexandria.py`: mint Org_Admin token (`client_secret=any` OIDC), import the
      size-N corpus into a clean org via the existing importer, promote to resolvable; verify
      count == N or halt.
    - *Files:* `experiments/harness/provision_alexandria.py`
    - *Requirements: 1.2, 1.4*

  - [ ] 5.2 Native sandbox provisioning
    - `harness/provision_native.py`: create an isolated workspace, copy the size-N corpus into
      `<workspace>/.claude/skills/<slug>/SKILL.md`; verify count == N or halt.
    - *Files:* `experiments/harness/provision_native.py`
    - *Requirements: 1.2, 1.4*

- [ ] 6. Run drivers (Claude Code headless)
  - [ ] 6.1 Alexandria driver + MCP capture proxy
    - `harness/run_alexandria.py`: run `claude -p --output-format json` with the Alexandria MCP server
      configured through a thin local proxy that forwards to Alexandria and logs `resolve_skill`
      payloads, the `fetch_skill` `skillVersionId`, and per-call HTTP timings (Retrieval_Latency).
      Emit a `RunRecord` (chosen_skill, resolve_candidates, latencies, reported usage, model id+ver).
    - *Files:* `experiments/harness/run_alexandria.py`, `experiments/harness/mcp_proxy.py`
    - *Requirements: 4.1, 4.2, 5.2, 5.4, 3.4, 6.4*

  - [ ] 6.2 Native driver + transcript parse
    - `harness/run_native.py`: run `claude -p --output-format json` in the native workspace; parse the
      transcript for the `Skill` dispatcher invocation (chosen_skill); measure matched-file
      read+parse time (Retrieval_Latency) and record the one-time frontmatter-preload cost separately;
      emit a `RunRecord`.
    - *Files:* `experiments/harness/run_native.py`
    - *Requirements: 4.1, 4.3, 4.4, 5.2, 5.4, 3.4, 6.4*

- [ ] 7. Metrics: tokens, scoring, aggregation
  - [ ] 7.1 Offline token measurement
    - `harness/measure_tokens.py`: pure function over (manifest, captured payloads, pinned tokenizer).
      Alexandria = tool schemas + resolve result + fetched body (resident constant in N). Native =
      Σ frontmatter over N (resident) + matched body. Exclude the common prompt/output.
    - *Files:* `experiments/harness/measure_tokens.py`
    - *Requirements: 3.1, 3.2, 3.3, 3.5*

  - [ ] 7.2 Scoring
    - `harness/score.py`: Model_Choice_Accuracy (`chosen == gold`, incl. `null` control) and
      Retrieval_Recall_at_K per (condition, N) with bootstrap CIs; native recall uses the modeled
      ranking with the caveat emitted.
    - *Files:* `experiments/harness/score.py`
    - *Requirements: 5.2, 5.3, 5.4, 5.5*

  - [ ] 7.3 Aggregation
    - `harness/aggregate.py`: from `records.jsonl`, per (metric, condition, N): token median+IQR +
      mean reported usage; latency median+IQR with cold/warm split; accuracy rate+CI. Warm-up
      excluded from primary stats but retained+labeled. Tidy CSV/JSON out.
    - *Files:* `experiments/harness/aggregate.py`
    - *Requirements: 2.3, 4.4, 6.2, 6.5, 9.6*

  - [ ]* 7.4 Unit tests: token purity, scoring, aggregation
    - `measure_tokens` fixed-input->fixed-output; native resident scales linearly with N and
      Alexandria resident constant on a synthetic manifest; `score` correct on hand-built records
      incl. no-skill-applies; re-aggregation is byte-identical.
    - *Requirements: 3.1, 3.2, 3.3, 5.2, 6.2, 7.4*

- [ ] 8. Entry point + end-to-end smoke
  - [ ] 8.1 `run_experiment` orchestrator
    - `harness/run_experiment.py`: validate task-set + provisioning, write `env.json` (machine, OS,
      model id+version together-or-neither, seeds, dep versions), run the matrix in seeded-random task
      order, write `results/raw/<run-id>/records.jsonl`; aggregation/scoring re-runnable standalone.
    - *Files:* `experiments/harness/run_experiment.py`
    - *Requirements: 6.1, 6.3, 6.4, 7.3, 7.4, 7.6*

  - [ ]* 8.2 Integration smoke (opt-in; needs stack + Claude Code)
    - Provision N=20 into both conditions; run 2 tasks × 1 rep; assert a RunRecord with non-null
      `chosen_skill`, positive `retrieval_latency_ms`, and a token cost per condition. Does not assert
      on the (non-deterministic) accuracy value.
    - *Requirements: 1.2, 4.2, 4.3, 5.4*

- [ ] 9. Author and freeze the Task_Set (v1)
  - [ ] 9.1 Write >=50 labeled tasks + labeling rationale
    - Fill `tasks/task_set.v1.json` with a mix of clear-match, near-variant-trap, and
      no-skill-applies control tasks; every `gold_skill` a Real_Tier slug or `null`; complete
      `LABELING.md`. Freeze v1.
    - *Files:* `experiments/tasks/task_set.v1.json`, `experiments/tasks/LABELING.md`
    - *Requirements: 5.1, 9.7, 9.9*

- [ ] 10. Protocol, results, and the run
  - [ ] 10.1 PROTOCOL.md + RESULTS_TEMPLATE.md
    - `PROTOCOL.md`: hypotheses, conditions, metrics, procedure, single-command run instructions.
      `RESULTS_TEMPLATE.md`: raw -> computed -> interpretation, with a threats-to-validity section
      (modeled native token/recall, tokenizer mismatch, LLM non-determinism, apples-to-oranges
      selection, single machine/model/client scope).
    - *Files:* `experiments/PROTOCOL.md`, `experiments/RESULTS_TEMPLATE.md`
    - *Requirements: 7.5, 8.1, 8.2, 8.3, 8.4*

  - [ ] 10.2 Full run at 20/100/500 and fill results
    - Run the full matrix; recompute aggregates from raw; fill a results report from the template;
      plot per-N token/latency curves and accuracy with CIs. Record env + seeds. (Needs the live
      stack, Claude Code CLI, and real API spend; driven manually via the single-command entry point.)
    - *Files:* `experiments/results/**` (raw + computed + filled report)
    - *Requirements: 2.3, 3.4, 4.4, 6.2, 8.1, 8.2, 8.3, 8.4*

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "3.1"] },
    { "id": 3, "tasks": ["3.2", "4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["4.3", "5.1", "5.2"] },
    { "id": 6, "tasks": ["6.1", "6.2"] },
    { "id": 7, "tasks": ["7.1", "7.2", "7.3"] },
    { "id": 8, "tasks": ["7.4", "8.1"] },
    { "id": 9, "tasks": ["8.2", "9.1"] },
    { "id": 10, "tasks": ["10.1"] },
    { "id": 11, "tasks": ["10.2"] }
  ]
}
```

## Notes

- **No product-code changes.** The harness uses only Alexandria's public API, the existing importer,
  and the `docker compose` stack. `./mvnw` is untouched (Requirement 7.2).

- **Reuse what exists.** Frontmatter parsing and the importer/OIDC (`client_secret=any`) flow are
  already proven in `scripts/import_anthropic_skills.py` and the manual-test docs; the harness reuses
  them rather than reinventing.

- **Determinism boundary.** Corpus assembly, padding, and task ordering are seeded and unit-tested.
  The LLM is not deterministic — that is precisely why N_rep, dispersion, and bootstrap CIs exist
  (Requirement 6.2). Do not attempt to "stabilize" the model.

- **Native token/recall are models of a documented mechanism** (preload of name+description for all N
  skills + progressive disclosure of one body), computed deterministically and **labeled as proxies**
  in the results. The likely bias direction (real disclosure may be leaner) is conservative for the
  Alexandria hypothesis; this is disclosed (Requirement 8.1, 8.3).

- **Accuracy is apples-to-oranges by construction** (native = LLM over resident descriptions;
  Alexandria = FTS rank then LLM fetch). This is the real product comparison, not a pure
  retrieval-algorithm benchmark; the results say so explicitly.

- **Capture via a local MCP proxy** (task 6.1) so payload/timing capture never depends on Claude Code
  exposing internals; the proxy forwards verbatim to Alexandria.

- **Raw is the source of truth.** All conclusions recompute from `records.jsonl`; `computed/` is
  regenerable and re-runs must be byte-identical (Requirement 7.4).

- **First pass is Claude Code only.** Kiro/Copilot slot in later as additional conditions (new run
  drivers) without changing metric definitions.
