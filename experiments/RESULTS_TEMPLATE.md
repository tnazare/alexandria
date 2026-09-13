# Skill Retrieval Benchmark — Results (run-id: __________)

> Copy this template to `results/<run-id>.md` and fill it in. Keep **measured facts** separate from
> **interpretation**. Report every metric as a function of library size N. Do not generalize beyond
> the tested model, client, corpus, and sizes.

## 1. Run metadata (from `results/raw/<run-id>/env.json`)

- Experiment version: `______`
- Date / machine / OS: `______`
- Model id + version: `______`  (if missing model provenance, mark the run **invalid**)
- Python + key dep versions (tiktoken, httpx): `______`
- Tokenizer encoding: `cl100k_base`
- Seeds (padding / order): `______`
- Library sizes: `______`   nrep: `___`   warmup: `___`   K: `___`
- Corpus content hashes per size: `______`
- Tier counts per size (real / distractor / padding): `______`

## 2. Raw data pointers

- Raw records: `results/raw/<run-id>/records.jsonl` (N records: ____)
- Computed aggregates: `results/computed/<run-id>/aggregates.{json,csv}`
- Task set: `tasks/task_set.v1.json` (version `v1`, 54 tasks)

## 3. Token cost (H1)

Primary = Context_Token_Cost (fixed tokenizer). Fill median [IQR] per condition per N.

| N | Alexandria (median [IQR]) | Native (median [IQR]) | Ratio native/alx |
|----|---------------------------|-----------------------|------------------|
| 20  | | | |
| 100 | | | |
| 500 | | | |

Corroboration — Claude reported usage (mean input tokens), same cells:

| N | Alexandria | Native |
|----|-----------|--------|
| 20 | | |
| 100 | | |
| 500 | | |

- Crossover / scaling observation: `______`
- Measured fact (one sentence): `______`

## 4. Retrieval latency (H2)

Primary = Retrieval_Latency (ms), cold and warm reported separately.

| N | Cond | Cold median [IQR] | Warm median [IQR] |
|----|------|-------------------|-------------------|
| 20  | alexandria | | |
| 20  | native     | | |
| 100 | alexandria | | |
| 100 | native     | | |
| 500 | alexandria | | |
| 500 | native     | | |

- Native one-time preload cost (ms), per N: `______` (reported separately, not per-task)
- End-to-end latency (context only): `______`
- Measured fact (one sentence): `______`

## 5. Accuracy (H3)

Primary = Model_Choice_Accuracy; secondary = Recall@K. Report rate and 95% bootstrap CI.

| N | Cond | Model-choice (rate [CI]) | Recall@K (rate [CI]) |
|----|------|--------------------------|----------------------|
| 20  | alexandria | | |
| 20  | native     | | |
| 100 | alexandria | | |
| 100 | native     | | |
| 500 | alexandria | | |
| 500 | native     | | |

Break out by task category (clear-match / near-variant-trap / no-skill-applies) if useful:

| Category | Alexandria | Native |
|----------|-----------|--------|
| clear-match | | |
| near-variant-trap | | |
| no-skill-applies | | |

- Measured fact (one sentence): `______`

## 6. Interpretation (kept separate from the numbers above)

- H1 (token cost): supported / not supported / mixed — because: `______`
- H2 (latency): supported / not supported / mixed — because: `______`
- H3 (accuracy): supported / not supported / mixed — because: `______`

## 7. Threats to validity (required)

- **Native token cost and recall are modeled, not scraped.** They follow Anthropic's documented
  preload + progressive-disclosure mechanism, computed deterministically. If Claude's real disclosure
  is leaner than modeled, native resident cost would be *lower* than reported — a bias that would
  *understate* Alexandria's advantage, so the direction is conservative for H1. Note magnitude if
  known: `______`
- **Tokenizer proxy.** `cl100k_base` ≈ but ≠ Claude's tokenizer; used only for the comparable
  cross-condition metric, with Claude's reported usage shown alongside.
- **LLM non-determinism.** Mitigated by nrep + dispersion + bootstrap CIs, not eliminated.
- **Accuracy is apples-to-oranges by construction.** Native = LLM choosing from resident
  descriptions; Alexandria = FTS ranking then LLM fetch. This is the real product comparison, not a
  pure retrieval-algorithm benchmark.
- **Scope.** Single machine, single model, Claude Code only, this corpus, these sizes. No claims
  beyond that.
- Any run-specific caveats (skips, failures, anomalies): `______`

## 8. Reproduce

```bash
# from experiments/, Python 3.11+ with requirements installed, stack up, claude on PATH
pytest
python -m harness.build_corpus --size 20 --seed 1234 --corpus corpus/20   # (+100, +500)
export TOKEN=$(curl -s -X POST http://localhost:9000/alexandria/token \
  -d grant_type=client_credentials -d client_id=alexandria-client -d client_secret=any -d scope=openid \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
python -m harness.run_experiment --config config/experiment.yaml
python -m harness.aggregate --records results/raw/<run-id>/records.jsonl --out results/computed/<run-id> --k 5
```
