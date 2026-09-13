# Skill Retrieval Benchmark — Protocol

This document is the scientific protocol for comparing **Alexandria** (server-side MCP skill library)
against the **native** `.claude/skills/` mechanism, for **Claude Code**, on three axes. It states the
hypotheses, conditions, metrics, and the exact step-by-step procedure so a third party can reproduce
the study.

## Hypotheses

Each is a falsifiable, directional hypothesis with library size **N** as the primary independent
variable.

- **H1 — Token cost.** Alexandria places fewer model-context tokens into play per task than native,
  and the gap widens with N (native resident cost grows ~linearly in N; Alexandria's is ~flat).
- **H2 — Retrieval latency.** Getting the chosen skill in front of the model is not slower under
  Alexandria than native (retrieval-only, excluding model generation).
- **H3 — Accuracy.** Alexandria is at least as accurate as native at leading the agent to use the
  correct skill (model-choice), and at surfacing it (recall@K).

Null results and reversals are reported as-is.

## Conditions

Both serve the **same corpus** (byte-identical `SKILL.md` files); only the mechanism differs.

- **Alexandria** — skills loaded into a running Alexandria stack; Claude Code sees two MCP tools
  (`resolve_skill`, `fetch_skill`) via a local capture proxy that forwards to the server.
- **Native** — the same corpus placed in an isolated workspace's `.claude/skills/`; Claude Code
  discovers them via its built-in preload + progressive-disclosure mechanism.

## Corpus (three tiers)

- **Real** — genuine harvested skills (seed: `anthropics/skills`, pinned commit). Only Real-tier
  skills can be a gold label.
- **Distractor** — realistic but task-wrong real skills (optional; add via `config/sources.yaml`).
- **Padding** — deterministically generated realistic *variant* skills (same concept, different
  language/framework) to scale N. Never a gold label.

Library sizes: **20 / 100 / 500** (configurable). The corpus is frozen per experiment version by a
`content_hash` recorded in every run.

## Metrics

| Axis | Primary | Secondary / corroborating |
|---|---|---|
| Token cost | **Context_Token_Cost** — one fixed tokenizer (`cl100k_base`), applied to both conditions, over the tokens each mechanism puts in context (excludes the shared prompt + model output). | Claude Code's own reported usage. |
| Latency | **Retrieval_Latency** — resolve+fetch HTTP time (Alexandria) / matched-file load time (native), excluding model generation; cold vs warm reported separately. | End-to-end wall-clock (context only). |
| Accuracy | **Model_Choice_Accuracy** — did the agent use the gold skill (or correctly use none). | **Recall@K** — was the gold skill in the surfaced candidate set. |

All accuracy signals are objective and logged (the `fetch_skill` argument for Alexandria; the `Skill`
dispatcher invocation in the native transcript) — never subjective grading.

## Fairness controls

Held constant across conditions: corpus content, task set, model + version, machine, prompt wording.
Repetitions (`nrep`) per (task, condition, N) cell quantify LLM variance; dispersion (median + IQR)
and bootstrap CIs are reported. Task order is seeded-random. Warm-up runs are excluded from primary
statistics but retained and labeled. Corpus/padding/order are seeded; the LLM is not deterministic —
which is exactly why repetitions exist.

## Prerequisites

- The Alexandria stack running locally: `docker compose up` (see the manual-test docs).
- The `claude` CLI on `PATH`, authenticated (the full run incurs real API usage).
- **Python 3.11+** with the pinned deps: `pip install -r requirements.txt`. (The default `python3`
  on some machines is older and will not run `tiktoken`; install a current Python first — see README.)

## Procedure

All commands run from `experiments/`.

### 0. Deterministic self-check (no stack, no spend)

```bash
pytest
```

Confirms the harness logic (corpus assembly, padding, token math, scoring, aggregation, validation)
is green before spending anything.

### 1. Build the corpus for each size

```bash
python -m harness.build_corpus --size 20  --seed 1234 --corpus corpus/20
python -m harness.build_corpus --size 100 --seed 1234 --corpus corpus/100
python -m harness.build_corpus --size 500 --seed 1234 --corpus corpus/500
```

Each writes `<corpus>/manifest.json` (tier, provenance, license, sha256) and the `SKILL.md` files.

### 2. Get an Org_Admin token (Alexandria condition)

The mock OIDC requires a client secret (any value):

```bash
export TOKEN=$(curl -s -X POST "http://localhost:9000/alexandria/token" \
  -d grant_type=client_credentials -d client_id=alexandria-client \
  -d client_secret=any -d scope=openid | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
```

### 3. Run the matrix

The orchestrator provisions each condition per size (halting if a corpus fails to load), runs each
task for `warmup + nrep` reps in seeded order, records raw `RunRecord`s and `env.json`:

```bash
python -m harness.run_experiment --config config/experiment.yaml
```

Raw output lands in `results/raw/<run-id>/records.jsonl` (+ `env.json`).

### 4. Fill Alexandria token cost offline (closes the capture gap)

The live loop records Alexandria retrieval latency and accuracy directly. **Context_Token_Cost for
the Alexandria condition is computed offline** from the fetched skill body (known via the captured
`skillVersionId` → slug → corpus file), the resolve-result size, and the two tool schemas, using the
pure functions in `harness.measure_tokens` (`alexandria_task_tokens`). The native condition's token
cost is filled during the run (it needs only the corpus files, per `native_task_tokens`).

> Implementation note: this offline step keeps the Alexandria token count **reproducible** and
> independent of Claude's internals. To make the fetched-body text directly available rather than
> re-read from the corpus, enable payload persistence in the capture proxy (`McpCaptureProxy`), which
> already records the `skillVersionId` and per-call timings; the body is otherwise recovered from the
> corpus file for that slug. Either path yields the same tokenizer count.

### 5. Aggregate

```bash
python -m harness.aggregate --records results/raw/<run-id>/records.jsonl \
  --out results/computed/<run-id> --k 5
```

Produces `aggregates.json` and `aggregates.csv`: per (metric, condition, N) median/IQR + mean for
token cost and latency (cold/warm split), and accuracy rate + bootstrap CI. Re-running is
byte-identical.

### 6. Write up results

Copy `RESULTS_TEMPLATE.md` to `results/<run-id>.md` and fill it from the computed aggregates. Keep
raw data and computed numbers separate from interpretation, and complete the threats-to-validity
section.

## What is measured vs. modeled (read before interpreting)

- **Alexandria** token cost, retrieval latency, model-choice, and recall are **direct observations**
  of a real run (captured payloads/timings; the fetched `skillVersionId`; the resolve candidate set).
- **Native** token cost and recall are **models of Anthropic's documented mechanism** (preload of
  every skill's name+description + progressive disclosure of one body), computed deterministically
  from the corpus files with the same tokenizer. They are labeled as models in the results. Native
  model-choice and matched-file latency are direct (transcript + filesystem).

The token comparison is thus a structural, reproducible computation; the accuracy comparison is a
real product comparison (native = LLM over resident descriptions; Alexandria = FTS rank then LLM
fetch), **not** a pure retrieval-algorithm benchmark. Say so when reporting.

## Cost & time

The full matrix is `sizes × conditions × tasks × (warmup + nrep)` Claude invocations
(default: 3 × 2 × 54 × 6 = 1944). Start smaller (e.g. `--sizes 20,100`, lower `nrep`) to validate the
pipeline before the full 500-skill run. Each invocation is real API usage.
