# Token-Scaling Curve — resident + per-task context cost vs N (no LLM)

> **Zero-API-spend** computation of H1 (token cost) across library sizes **N = 20/50/100/200/500**,
> using only the deterministic token model (`harness.measure_tokens`) over the frozen 500-skill
> superset. This is the scaling test the full 500-skill LLM matrix was meant to provide, done offline
> — valid because `context_token_cost` is modeled, not scraped from Claude (see PROTOCOL "What is
> measured vs. modeled"). Reproduce with `harness.token_scaling`.

## 1. Metadata

- Tokenizer: `cl100k_base` (same fixed encoding as the LLM runs)
- Superset: `corpus/superset` (500 real skills), sample seed `1234`
- No stack, no `claude` CLI, no API usage. Deterministic: same superset + sizes + seed → identical output.
- Model of one task's context cost = **resident** (always in context) + **per_task** (one disclosed body).
  - native resident = Σ over all N skills of tokens(name + description) — the startup preload.
  - alexandria resident = tokens(resolve_skill schema) + tokens(fetch_skill schema) — constant in N.
  - per_task uses the **median** body in the sample for both conditions, so the body cost is identical
    and the curve isolates the *resident* difference (the thing that scales).

## 2. Result (seed 1234)

| N | native resident | native total | alx resident | alx total | ratio native/alx |
|----|----------------:|-------------:|-------------:|----------:|-----------------:|
| 20 | 820 | 2101 | 200 | 1766 | 1.19× |
| 50 | 1948 | 3252 | 200 | 1789 | 1.82× |
| 100 | 3849 | 5290 | 200 | 1926 | 2.75× |
| 200 | 8260 | 10094 | 200 | 2319 | 4.35× |
| 500 | 19124 | 20819 | 200 | 2180 | 9.55× |

**Crossover N ≈ 15** (stable across seeds 1234/42/999). Below ~15 skills native is cheaper (its
per-skill preload is tiny and Alexandria pays a fixed ~200-token schema floor plus a resolve payload).
At N ≥ 15 native's linear preload overtakes Alexandria's flat resident cost, and the gap widens
without bound: ~1.2× at N=20, ~9.5× at N=500.

## 3. Interpretation

- **H1 confirmed at scale, structurally.** Native resident cost grows ~linearly in N
  (820 → 19,124 tokens from N=20 → 500); Alexandria resident cost is flat (~200 tokens, the two tool
  schemas). This is the architectural advantage the registry was premised on, and it is real on the
  token axis.
- **But it only bites past the crossover.** At the N=20 used in every LLM run so far, the native
  preload is just ~820 resident tokens — cheap in absolute terms. The dramatic 9.5× gap needs N≈500 to
  appear. So the token argument for a server-side registry is a **large-library** argument; it does not
  justify the architecture at small N.
- **This does not touch accuracy/compliance.** The LLM runs showed native wins model-choice because its
  catalog is resident (consult "for free"), while Alexandria's explicit tool call is skipped ~40% of
  the time. That gap is roughly N-independent and is *not* addressed by this curve. The token win and
  the accuracy loss live on different axes.
- **Takeaway.** The scaling claim holds where it matters (big libraries), but Alexandria's problem was
  never retrieval tokens — it is getting the model to consult at all. The resident-catalog idea (inject
  a compact name+description catalog like native, then fetch bodies on demand) is attractive precisely
  because it keeps native's compliance behavior while, with hierarchical disclosure, it could stay
  sub-linear in N and avoid native's linear preload. That is the next thing worth prototyping.

## 4. Reproduce

```bash
cd experiments
.venv/bin/python -m harness.token_scaling \
    --superset corpus/superset --sizes 20,50,100,200,500 --seed 1234 \
    --out results/token-scaling
# outputs results/token-scaling/token_scaling.{json,csv} and prints the table + crossover
```

## 5. Threats to validity

- Token cost is **modeled**, not Claude's private tokenizer (`cl100k_base` proxy) — same caveat as all
  prior runs; it is a comparable, reproducible proxy.
- Per-task body uses the sample **median** so a lucky/unlucky matched skill doesn't move the curve;
  real tasks disclose a specific body, but that affects both conditions identically.
- The native preload model counts name+description per skill (the documented mechanism); it omits
  minor structural framing tokens, which would only *increase* native's resident cost and push the
  crossover slightly lower — i.e. the curve is conservative toward native.
- Resident cost is the context *floor*; it does not model multi-turn accumulation, which would amplify
  native's disadvantage further at large N.
