# Requirements Document

## Introduction

This specification covers the **Skill Retrieval Benchmark** — a scientific experiment that compares
Alexandria's server-side MCP skill library against the *native* file-based skill-library mechanisms
built into agent clients (`.claude/skills/` for Claude Code, `.kiro/skills/` for Kiro,
`.github/skills/` for Copilot). It answers three questions, each as a falsifiable hypothesis with a
clean, reproducible protocol:

1. **Token cost** — Does retrieving skills through Alexandria consume *fewer model-context tokens*
   than the native library, and how does that scale with library size?
2. **Retrieval latency** — Is getting the right skill in front of the model *faster* through
   Alexandria than through the native library?
3. **Accuracy** — Is Alexandria *more accurate* at surfacing/selecting the correct skill for a task
   than the native library?

The two approaches differ architecturally, and the protocol must respect that difference to be fair.
The **native** mechanism injects skill definitions into the model's context (all up-front, or via a
client-specific progressive-disclosure scheme) and the model reasons over that context to pick a
skill. **Alexandria** exposes only two tools (`resolve_skill`, `fetch_skill`); retrieval is a
server-side Postgres full-text ranked search that returns a short candidate list, after which the
model fetches exactly one body on demand. Token cost is therefore *front-loaded and library-size
dependent* for native, and *per-retrieval and roughly flat in library size* for Alexandria. A valid
experiment must treat **library size as an independent variable**, not a fixed constant.

**Scope of this pass.** The first pass targets **Claude Code only**, for all three metrics. The
harness and protocol are designed so Kiro and Copilot can be added later as additional *conditions*
without changing the metric definitions. Every artifact needed to run, inspect, and reproduce the
experiment — harness code, task set, fixtures, protocol doc, and results templates — lives in a
dedicated top-level `experiments/` folder, so that a third party can audit the methodology and
re-run it.

**Corpus.** Because there is no single large public repository of genuine, loadable `SKILL.md` files
(the large "awesome-skills" collections are curated *link lists*, not corpora of files), the
experiment assembles its corpus in three tiers: a **Real tier** of genuine skills harvested from
documented public sources (starting with `anthropics/skills` and expanding via official/vendor skill
links), a **Distractor tier** of realistic-but-wrong skills that make accuracy non-trivial, and a
**Padding tier** of deterministically generated *variant* skills (the same concept across different
languages/frameworks/technologies) used only to scale library size. Only Real-tier skills can be a
gold label; padding is never a correct answer. This keeps gold labels few, real, and auditable while
letting Library_Size scale to 500+.

**Relationship to existing work.** The experiment reuses the already-imported Anthropic skills as the
seed of the Real tier, the Alexandria MCP endpoint (`POST /api/agent/v1/mcp`), the service-token
minting API, the importer, and the `docker compose` local stack. It does **not** modify any
Alexandria product code; it only adds experiment tooling under `experiments/`.

## Glossary

- **System_Under_Test (SUT)**: One of the retrieval mechanisms being compared.
- **Alexandria_Condition**: Skills served via the Alexandria MCP endpoint (`resolve_skill` /
  `fetch_skill`); the client sees two tools, not skill bodies.
- **Native_Condition**: Skills served by the client's built-in file-based skill library
  (`.claude/skills/` for the Claude Code first pass), where skill definitions enter the model context.
- **Corpus**: The shared set of skills used in every condition, held byte-identical across conditions
  so only the *mechanism* differs. Composed of three tiers (Real, Distractor, Padding).
- **Real_Tier**: Genuine harvested skills with recorded provenance and license; the only tier
  eligible to contain a Gold_Skill.
- **Distractor_Tier**: Realistic but task-wrong skills included to make accuracy non-trivial.
- **Padding_Tier**: Deterministically generated variant skills (same concept, different
  language/framework/technology) used only to scale Library_Size; never a Gold_Skill.
- **Harvester**: The tool that collects Real_Tier `SKILL.md` skills from documented public sources
  and records their provenance and license.
- **Library_Size (N)**: The number of skills present in the corpus for a given run; the primary
  independent variable (e.g. 20 / 100 / 500), scaled by combining the tiers.
- **Task**: One benchmark item: a natural-language coding request plus a **Gold_Skill** label (the
  single skill a correct system should surface/select), or an explicit "no skill applies" label.
- **Task_Set**: The versioned collection of Tasks, stored as data under `experiments/`, with a
  documented labeling rationale.
- **Gold_Skill**: The correct skill for a Task, identified by its stable slug (e.g. `pdf`), agreed
  before any runs and frozen for the experiment version.
- **Model_Choice_Accuracy**: The primary accuracy metric — whether the agent actually *used/invoked*
  the Gold_Skill when completing the Task (Alexandria: fetched the Gold_Skill's version; native: the
  model applied that skill).
- **Retrieval_Recall_at_K**: The secondary accuracy metric — whether the Gold_Skill appeared in the
  candidate set surfaced to the model (Alexandria: in `resolve_skill` results; native: available in
  the injected/considered set), for a fixed K.
- **Context_Token_Cost**: The primary token metric — a tokenizer-counted number of tokens each SUT
  places into the model context to service a Task, computed offline from captured payloads with a
  single fixed tokenizer.
- **Reported_Token_Usage**: The secondary/corroborating token metric — token counts as reported by
  Claude Code's own usage accounting for a Task run.
- **Retrieval_Latency**: The primary latency metric — wall-clock time to get the chosen skill in
  front of the model, excluding model generation (Alexandria: `resolve` + `fetch` round-trips;
  native: context assembly / file load), measured on a fixed machine.
- **End_to_End_Latency**: A secondary latency metric — total task wall-clock including model
  generation (reported for context only, not the primary comparison).
- **Run**: One execution of one Task under one Condition at one Library_Size; the atomic unit of
  measurement.
- **Repetition (N_rep)**: Repeated Runs of an identical (Task, Condition, Library_Size) cell used to
  quantify variance.
- **Cold_Run / Warm_Run**: A Run against a freshly started system (caches empty) versus a system
  that has already served requests; reported separately.
- **Harness**: The reproducible code under `experiments/` that provisions conditions, executes Runs,
  captures raw measurements, and computes metrics.

## Requirements

### Requirement 1: Comparable Conditions Over a Shared Corpus

**User Story:** As an experimenter, I want each system compared over the exact same skill content, so
that any measured difference is attributable to the *mechanism* and not to different skills.

#### Acceptance Criteria

1. THE Harness SHALL use one Corpus of byte-identical `SKILL.md` files across the Alexandria_Condition
   and the Native_Condition; the same slugs, names, descriptions, and bodies SHALL be present in both.
2. THE Harness SHALL provision the Alexandria_Condition by loading the Corpus into Alexandria (via the
   existing importer) and promoting the skills so they are resolvable, and SHALL provision the
   Native_Condition by placing the same Corpus files into the client's native skills directory
   (`.claude/skills/` for the first pass).
3. THE Harness SHALL record, for each Run, which Condition, which Library_Size, and which exact Corpus
   version (content hash) were in effect, so a Run is fully attributable.
4. IF loading the Corpus into any Condition fails, THEN THE Harness SHALL halt the experiment and
   SHALL NOT proceed with other Conditions or Runs, so that no results are produced from an
   incompletely provisioned Corpus.
5. WHERE a Condition cannot hold a skill byte-identical due to a mandatory client-specific format
   difference, THE Harness SHALL document the transformation applied and SHALL keep it deterministic
   and disclosed in the results.

### Requirement 2: Library Size as an Independent Variable

**User Story:** As an experimenter, I want to measure each metric at several library sizes, so that I
can report how the comparison changes as the catalog grows rather than at a single point.

#### Acceptance Criteria

1. THE Harness SHALL support running the full protocol at multiple Library_Sizes, scaled by combining
   the Real_Tier, Distractor_Tier, and Padding_Tier, across a documented set of sizes (e.g.
   20 / 100 / 500) as defined in Requirement 9.
2. WHEN Library_Size is scaled with Padding_Tier skills, THE Harness SHALL generate them
   deterministically and SHALL ensure no Padding_Tier (or Distractor_Tier) skill is ever a Gold_Skill
   for any Task.
3. THE Harness SHALL report every metric as a function of Library_Size, not as a single aggregate
   that hides the size dependence.
4. THE Padding_Tier generator SHALL be seeded so that a given Library_Size produces the same corpus
   on every run.

### Requirement 3: Token-Cost Measurement (Primary and Corroborating)

**User Story:** As an experimenter, I want a reproducible, client-independent token count plus a
real-world corroboration, so that the token-cost claim is both rigorous and credible.

#### Acceptance Criteria

1. THE Harness SHALL compute Context_Token_Cost offline from the captured payloads using a single,
   fixed, documented tokenizer, and SHALL apply that same tokenizer to both Conditions.
2. FOR the Alexandria_Condition, Context_Token_Cost SHALL count the tokens the mechanism introduces
   into context to service a Task: the two tool schemas, the `resolve_skill` result payload, and the
   fetched skill body — and SHALL exclude the task prompt and model output that are common to both
   Conditions.
3. FOR the Native_Condition, Context_Token_Cost SHALL count the skill-definition tokens the client
   places into context to service a Task under that client's disclosure model, measured or faithfully
   emulated, and SHALL exclude the common task prompt and model output.
4. THE Harness SHALL capture Reported_Token_Usage from Claude Code's own accounting for each Run where
   available, and SHALL report it alongside Context_Token_Cost as corroboration, clearly labeled as a
   separate measurement.
5. THE token-counting method for each Condition SHALL be documented precisely enough that a third
   party can reproduce the same counts from the same captured payloads.

### Requirement 4: Retrieval-Latency Measurement (Primary) and End-to-End (Secondary)

**User Story:** As an experimenter, I want to isolate the time each mechanism takes to put the right
skill in front of the model, so that latency reflects the systems and not model-generation noise.

#### Acceptance Criteria

1. THE Harness SHALL measure Retrieval_Latency as the primary latency metric, excluding model
   generation time.
2. FOR the Alexandria_Condition, Retrieval_Latency SHALL be the measured wall-clock of the
   `resolve_skill` call plus the single `fetch_skill` call, measured at the HTTP client boundary.
3. FOR the Native_Condition, Retrieval_Latency SHALL be the measured wall-clock of the client
   assembling/loading the relevant skill definition(s) into context for the Task, using the closest
   faithful measurement the client permits, with the method documented.
4. THE Harness SHALL separate Cold_Run and Warm_Run latencies and SHALL report them distinctly.
5. THE Harness MAY additionally record End_to_End_Latency for context, but SHALL NOT use it as the
   primary latency comparison.
6. THE Harness SHALL record latencies on a single fixed machine per experiment run and SHALL record
   the machine/environment characteristics with the results.

### Requirement 5: Accuracy Measurement (Model-Choice Primary, Recall Secondary)

**User Story:** As an experimenter, I want to know whether each system leads the agent to actually use
the right skill, and secondarily whether the right skill was even available, so that I can separate
retrieval quality from model decision.

#### Acceptance Criteria

1. THE Harness SHALL use a labeled Task_Set in which every Task has a single Gold_Skill or an explicit
   "no skill applies" label, frozen for the experiment version.
2. THE primary accuracy metric SHALL be Model_Choice_Accuracy: whether the agent used/invoked the
   Gold_Skill for the Task (for "no skill applies" Tasks, whether the agent correctly used none).
3. THE secondary accuracy metric SHALL be Retrieval_Recall_at_K: whether the Gold_Skill appeared in
   the candidate set surfaced to the model, for a fixed, documented K, computed per Condition.
4. THE Harness SHALL determine Model_Choice_Accuracy from an objective, logged signal of which skill
   the agent used (e.g. the `fetch_skill` call's `skillVersionId` for Alexandria; a captured
   skill-invocation signal for native), and SHALL NOT rely on subjective grading of output quality.
5. WHERE an objective signal of native skill invocation is not exposed by the client, THE Harness
   SHALL document the fallback determination method and its limitations in the results.
6. THE Task_Set SHALL be versioned, stored as data under `experiments/`, and carry a documented
   labeling rationale; IF any of these — version, stored location, or labeling rationale — is
   missing, THEN THE Harness SHALL reject the Task_Set and SHALL NOT execute Runs against it.

### Requirement 6: Fairness Controls and Statistical Validity

**User Story:** As a reviewer, I want the confounds controlled and the variance quantified, so that I
can trust the conclusions.

#### Acceptance Criteria

1. THE Harness SHALL hold constant across Conditions: the Corpus content, the Task_Set, the model and
   model version, the machine/environment, and the task prompt wording.
2. THE Harness SHALL execute a configurable number of Repetitions (N_rep ≥ a documented minimum) per
   (Task, Condition, Library_Size) cell and SHALL report central tendency and dispersion (e.g. median
   and inter-quartile range) rather than a single sample.
3. THE Harness SHALL randomize Task execution order within a run and SHALL record the seed used, so
   ordering effects are controlled and reproducible.
4. THE Harness SHALL record the model identifier and version together for every Run; IF the model
   identifier is absent, THEN THE Harness SHALL NOT record a version and SHALL flag the Run as
   missing model provenance; and IF the model changed mid-experiment, THEN THE Harness SHALL flag the
   results as invalid.
5. THE Harness SHALL separate warm-up Runs from measured Runs and SHALL exclude warm-up Runs from the
   primary statistics; warm-up data MAY still be included in the results output PROVIDED it is
   clearly labeled as warm-up and kept distinct from measured Runs.

### Requirement 7: Reproducibility and Inspectability

**User Story:** As a third party, I want to inspect the experiment code and re-run it to the same
conclusions, so that the results are credible and auditable.

#### Acceptance Criteria

1. ALL experiment code, task data, fixtures, configuration, protocol documentation, and results
   templates SHALL live under a single top-level `experiments/` folder, separate from Alexandria
   product code.
2. THE experiment SHALL NOT modify Alexandria product code; it SHALL interact with Alexandria only
   through its public API and the existing importer/stack.
3. THE Harness SHALL be runnable via a documented, single-command entry point (with parameters for
   Condition, Library_Size, and N_rep) and SHALL pin its dependencies (tokenizer, language runtime)
   so counts are stable.
4. THE Harness SHALL persist raw per-Run measurements (not just aggregates) to disk in a documented,
   machine-readable format, so anyone can recompute the aggregate metrics.
5. THE `experiments/` folder SHALL include a protocol document describing the hypotheses, conditions,
   metrics, procedure, and how to run it, plus a results template that separates raw data from
   computed conclusions.
6. THE Harness SHALL be deterministic where possible (seeded padding, seeded ordering) and SHALL
   record all seeds and versions needed to reproduce a run.

### Requirement 8: Honest Reporting of Threats to Validity

**User Story:** As a reader of the results, I want the experiment to state what it could and could not
control, so that I do not over-read the conclusions.

#### Acceptance Criteria

1. THE results document SHALL state the threats to validity, including any Condition where a metric
   had to be emulated rather than directly measured (e.g. native token accounting, native invocation
   signal).
2. THE results document SHALL scope conclusions to the tested model, client (Claude Code), Corpus, and
   Library_Sizes, and SHALL NOT generalize beyond them without saying so.
3. WHERE a measurement is a proxy rather than a direct observation, THE results document SHALL label
   it as a proxy and describe the expected direction and rough magnitude of any bias.
4. THE results document SHALL distinguish measured facts from interpretation.

### Requirement 9: Corpus Sourcing and Scale

**User Story:** As an experimenter, I want a corpus large enough to stress library size while keeping
gold labels trustworthy, so that the size-dependence is real and the accuracy labels remain auditable.

#### Acceptance Criteria

1. THE Corpus SHALL be composed of three clearly-separated tiers, each recorded per skill: a
   **Real_Tier** of genuine skills with provenance, a **Distractor_Tier** of realistic but
   task-wrong skills, and a **Padding_Tier** of deterministically generated variant skills used only
   to scale Library_Size.
2. THE Harness SHALL include a **Harvester** that collects real `SKILL.md` skills from documented
   public sources (starting with `anthropics/skills` and expanding by following official/vendor
   skill links referenced in curated lists), and SHALL record for each harvested skill its source
   URL, commit/ref, and license.
3. THE Harvester SHALL skip sources it cannot fetch or whose format it cannot parse, SHALL log what
   it skipped and why, and SHALL never silently include a malformed skill.
4. ONLY skills in the Real_Tier SHALL be eligible to be a Gold_Skill; Distractor_Tier and
   Padding_Tier skills SHALL never be a Gold_Skill for any Task.
5. THE Padding_Tier generator SHALL produce **realistic variant skills** — e.g. the same skill
   concept expressed for a different programming language, framework, or technology — that are
   deterministically distinct from the real skills and from each other, seeded so a given
   Library_Size reproduces the same corpus.
6. THE Harness SHALL support scaling Library_Size up to at least 500 skills by combining the tiers,
   and SHALL report metrics across a documented set of sizes (e.g. 20 / 100 / 500).
7. WHERE a variant skill is generated from a real skill concept, THE gold labeling SHALL remain
   unambiguous: a Task's Gold_Skill SHALL pin the exact expected skill slug, so that a near-variant
   in the Distractor_Tier or Padding_Tier is scored as wrong if selected.
8. THE Harness SHALL record and surface per-skill license/attribution for all Real_Tier skills in
   the corpus manifest, so redistribution and reuse can be audited.
9. THE Task_Set SHALL contain at least 50 labeled Tasks for the first pass.

## Non-Goals

The following are explicitly out of scope for this spec:

- **Kiro and Copilot in the first pass.** The protocol and harness are designed to add them later as
  Conditions, but the first pass measures Claude Code only. Only the Claude Code native mechanism
  (`.claude/skills/`) is exercised now.
- **Subjective output-quality grading.** Accuracy is defined objectively (which skill was used /
  surfaced), not by rating how good the generated code is.
- **End-to-end latency as a primary metric.** It may be recorded for context but is dominated by model
  generation and is not the comparison of record.
- **Changes to Alexandria product code, schema, or the resolver ranking.** The experiment measures the
  system as built; tuning the ranker is a separate effort.
- **A hosted/CI-automated benchmark or dashboard.** The deliverable is a locally reproducible harness
  and protocol, not a continuous benchmarking service.
- **Model fine-tuning or prompt optimization** to favor either Condition; prompts are held identical
  and disclosed.
- **Cost-in-dollars modeling.** Token counts are reported; converting them to pricing is left to the
  reader.
