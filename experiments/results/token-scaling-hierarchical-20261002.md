# Token-Scaling — Hierarchical Disclosure vs Catalog / Native / Alexandria (no LLM)

> **Zero-API-spend** extension of the token-scaling curve (`results/token-scaling-20261001.md`) adding
> a fourth mechanism — **hierarchical disclosure** — to test whether it recovers Alexandria's flat-in-N
> token cost while keeping the catalog's resident-selection structure. Computed from the deterministic
> token model over the frozen 500-skill superset; categories derived by seeded k-means over the
> committed embeddings. Reproduce with `harness.token_scaling`.

## 1. Mechanism

Hierarchical disclosure clusters the N skills into **C ≈ √N categories**. Only the **C category
headers** (`label (count): summary`) stay resident. Two tools: `list_category(category)` discloses one
category's `id: name — description` lines on demand; `fetch_skill(id)` returns one body. The agent:
reads resident headers → opens one category → picks a skill → fetches it. Resident cost is **O(C)**,
not O(N) — but selection still begins from resident text (the headers), the property that gave the
catalog condition its compliance.

## 2. Result (seed 1234, cl100k_base)

| N | C | native resident | catalog resident | **hier resident** | native total | catalog total | **hier total** | catalog/hier resident |
|----|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 4 | 820 | 1012 | **147** | 2101 | 2293 | **1717** | 6.9× |
| 50 | 7 | 1948 | 2324 | **180** | 3252 | 3628 | **1749** | 12.9× |
| 100 | 10 | 3849 | 4531 | **194** | 5290 | 5972 | **1972** | 23.4× |
| 200 | 14 | 8260 | 9573 | **240** | 10094 | 11407 | **2891** | 39.9× |
| 500 | 22 | 19124 | 22259 | **310** | 20819 | 23954 | **2984** | 71.8× |

## 3. Interpretation

- **Hierarchical disclosure bends resident cost sub-linear — the design goal.** Hier resident stays
  ~flat (147 → 310 tokens from N=20 → 500) because only ~√N category headers are resident, while
  native and catalog grow linearly (820/1012 → 19,124/22,259). At N=500 the hierarchical scheme's
  resident cost is **72× smaller than the flat catalog's**.
- **Total cost tracks Alexandria, not native/catalog.** Hier total (resident + one opened category's
  lines + one body) is ~1,700 at N=20 and ~2,980 at N=500 — essentially flat, and at N=500 it is
  **~8× cheaper** than both native (20,819) and catalog (23,954), landing near Alexandria's flat
  ~2,180. Hierarchical disclosure recovers the server-side flat-in-N property **client-side**, in the
  prompt, without a backend.
- **Why this matters for the thesis.** The catalog A/B (`run-20261002-095819.md`) showed resident
  selection beats native on compliance/accuracy but at a token premium that, like native, grows with
  N. Hierarchical disclosure keeps the resident-selection shape (headers are resident) while removing
  the linear-in-N penalty. On the token axis it is the best of both: catalog's structure, Alexandria's
  scaling.

## 4. What is NOT yet shown (honest scope)

- **This is the token model only.** The compliance/accuracy claim — that opening a category then
  selecting still consults as reliably as the flat catalog — is **not** tested here and needs a live
  LLM run. Adding a disclosure step (open category → then pick) could lower compliance (one more tool
  call to skip) or raise it (smaller, focused choice set). That A/B is the next experiment.
- **Per-task cost assumes the agent opens exactly one category** (the median-sized one, modeled). If a
  task makes the agent open several categories, per-task cost rises toward the flat catalog. The
  resident win is unconditional; the total-cost win depends on disclosure discipline.
- **Category quality depends on the embedding clustering.** Labels are deterministic keyword summaries;
  a vague cluster could mislead category selection. Measured only structurally here.

## 5. Reproduce

```bash
cd experiments
.venv/bin/python -m harness.token_scaling --sizes 20,50,100,200,500 --seed 1234 \
    --out results/token-scaling-hier
# categories via harness.categorize (seeded k-means over corpus/embeddings/embeddings.json)
```
