# Skill Retrieval Benchmark

A reproducible experiment comparing two ways of putting the *right skill* in front of a coding agent,
measured on **token cost**, **retrieval latency**, and **accuracy** — for **Claude Code** in this
first pass.

- **Alexandria condition** — skills live server-side; the client sees two MCP tools
  (`resolve_skill`, `fetch_skill`). Retrieval is a server-side Postgres full-text ranked search; the
  model fetches one skill body on demand.
- **Native condition** — skills live in `.claude/skills/`. Claude pre-loads every skill's
  `name`+`description` into its system prompt at startup and reads a full `SKILL.md` only on match
  (progressive disclosure).

The experiment treats **library size (N)** as the primary independent variable and reports every
metric as a function of N.

See [`PROTOCOL.md`](./PROTOCOL.md) for the full method and [`RESULTS_TEMPLATE.md`](./RESULTS_TEMPLATE.md)
for the write-up skeleton. This folder contains **all** experiment code and data; it does **not**
modify any Alexandria product code.

## Layout

```
experiments/
  README.md                 # this file
  PROTOCOL.md               # hypotheses, conditions, metrics, procedure (task 10)
  RESULTS_TEMPLATE.md       # results write-up skeleton (task 10)
  requirements.txt          # pinned Python deps
  pyproject.toml            # package + pytest config
  config/
    experiment.yaml         # sizes, nrep, K, seeds, model id, host, warmup
    sources.yaml            # pinned harvest sources (task 3)
  corpus/                   # generated: real/ distractor/ padding/ + manifest.json (task 4)
  tasks/
    task_set.v1.json        # >=50 labeled tasks (task 9)
    LABELING.md             # labeling rationale (task 9)
  harness/                  # the code
  results/                  # generated: raw/<run-id>/ + computed/<run-id>/
  tests/                    # pytest for the deterministic parts
```

## Setup

Requires **Python 3.11+** (for `tiktoken` and modern typing). The default `python3` on some machines
is older (e.g. 3.7) and will not work — install a current Python first, for example:

```bash
brew install python@3.12         # macOS/Homebrew
# or use pyenv / uv to provision 3.12
```

Then create the environment with that interpreter:

```bash
cd experiments
python3.12 -m venv .venv && source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
```

> The deterministic modules (`models.py`, task-set validation, corpus building) import fine on older
> Pythons because the `tiktoken` import is lazy — but token counting and the full run need 3.11+.
> To run just the deterministic unit tests on an older interpreter, a compatible pytest works:
> `python3 -m venv .venv && . .venv/bin/activate && pip install "pytest==7.4.4" && pytest tests/test_tasks.py`.

## Running

Deterministic unit tests (no stack, no API spend):

```bash
pytest
```

Full experiment (needs the running `docker compose` stack + the `claude` CLI + API spend):

```bash
python -m harness.run_experiment --config config/experiment.yaml
```

See `PROTOCOL.md` for the step-by-step procedure and the meaning of every flag.

## Reproducibility

Corpus assembly, padding generation, and task ordering are **seeded**. The LLM itself is not
deterministic — that is why the protocol uses repetitions and reports dispersion. Every run records
its environment, seeds, and dependency versions under `results/raw/<run-id>/env.json`, and all
aggregates are recomputable from the raw `records.jsonl`.
