"""Auto-derive fixed, gold-labeled task sets per subset (task 4).

For each named subset, generate one benchmark task per selected gold skill: a natural-language user
request *derived from that skill's own description*, with the gold label = that skill's slug (always
in-subset by construction). Prompts are templated (deterministic, no LLM, no token cost) by reframing
the skill's description as a request, and the skill name/slug is scrubbed so the match is non-trivial
(the agent must recognize the task, not pattern-match the name).

Output: ``tasks/subsets/<subset-name>.json`` — a frozen, committed task set in the same schema the
harness validates (version, labeling_doc, tasks[{id, prompt, gold_skill, rationale, category}]).

Sizing: size-20 subsets get one task per skill (20 tasks); size-100 subsets get a deterministic
seeded sample (default 40) so run cost stays bounded. All seeded and byte-stable.
"""

from __future__ import annotations

import json
import logging
import random
import re
from pathlib import Path

log = logging.getLogger("harness.derive_tasks")

TASKS_DIR = "tasks/subsets"
SUBSETS_DIR = "corpus/subsets"
SUPERSET_MANIFEST = "corpus/superset/manifest.json"
SAMPLE_FOR_LARGE = 40          # tasks generated for size-100 subsets (bounded run cost)
DERIVE_SEED = 1234

# Request framings rotated deterministically so prompts read naturally and vary.
FRAMINGS = [
    "I need to {}",
    "Help me {}",
    "Can you {}",
    "I want to {}",
    "Please {}",
    "My task is to {}",
]


def _first_sentence(text: str) -> str:
    text = (text or "").strip()
    if not text:
        return ""
    # split on sentence end; keep the first meaningful clause.
    parts = re.split(r"(?<=[.!?])\s+", text)
    return parts[0].strip() if parts else text


# Meta-prefixes that describe the skill rather than the task; stripped before framing.
_META_PREFIX = re.compile(
    r"^(this skill\s+|this\s+(is\s+a\s+)?|a\s+(structured\s+)?(guide|workflow|skill|tool|framework)\s+(for|to|that)\s+"
    r"|use this (skill|tool) (to|for|when|whenever)\s+|you are\s+|you bring\s+)",
    re.IGNORECASE,
)

# Common 3rd-person verb endings -> base form, so "Builds X" reads as "build X" after a framing.
_VERB_FIX = {
    "builds": "build", "implements": "implement", "generates": "generate", "creates": "create",
    "provides": "provide", "helps": "help", "guides": "guide", "manages": "manage",
    "analyzes": "analyze", "reviews": "review", "handles": "handle", "sets": "set",
    "writes": "write", "designs": "design", "tests": "test", "automates": "automate",
    "converts": "convert", "extracts": "extract", "validates": "validate", "optimizes": "optimize",
    "ensures": "ensure", "runs": "run", "performs": "perform", "produces": "produce",
}

# A verb we can safely drop into "I need to {}" — present-tense base forms.
_KNOWN_VERBS = set(_VERB_FIX.values()) | {
    "build", "implement", "generate", "create", "provide", "help", "guide", "manage", "analyze",
    "review", "handle", "set", "write", "design", "test", "automate", "convert", "extract",
    "validate", "optimize", "ensure", "run", "perform", "produce", "add", "configure", "deploy",
    "refactor", "debug", "document", "integrate", "parse", "render", "fetch", "scaffold",
}


def _to_request(description: str, name: str, slug: str, framing: str) -> str:
    """Turn a skill description into a natural task request, scrubbing the skill's own name/slug.

    Handles the grammatical variety of real descriptions: strips meta-prefixes ("This skill...",
    "A guide for..."), normalizes a 3rd-person leading verb to base form, and when the text doesn't
    begin with a clean verb, falls back to a neutral framing that reads naturally regardless.
    """
    s = _first_sentence(description) or name

    # Scrub the skill name and slug tokens so the prompt doesn't trivially leak the answer.
    for token in {name, slug, slug.replace("-", " "), name.replace("-", " ")}:
        if token and token.strip():
            s = re.sub(re.escape(token), "", s, flags=re.IGNORECASE)
    # Strip meta-prefixes that describe the skill rather than the task.
    s = _META_PREFIX.sub("", s).strip()
    s = re.sub(r"\s{2,}", " ", s).strip().strip(".").strip()
    if not s:
        s = (name or slug).replace("-", " ")

    first, _, rest = s.partition(" ")
    low = first.lower()
    if low in _VERB_FIX:                       # "Builds X" -> "build X"
        s = _VERB_FIX[low] + (" " + rest if rest else "")
        return framing.format(s[:1].lower() + s[1:]) + "."
    if low in _KNOWN_VERBS:                     # already a usable verb ("Implement X")
        return framing.format(s[:1].lower() + s[1:]) + "."
    # Not verb-initial (noun phrase, adjective, etc.) -> neutral framing that always reads cleanly.
    return f"I have a task that involves {s[:1].lower() + s[1:]}."


def derive_for_subset(subset: dict, superset_by_slug: dict, *, seed: int) -> dict:
    members = subset["skills"]
    size = subset["size"]
    rng = random.Random(seed)

    if size <= 20:
        selected = list(members)
    else:
        selected = rng.sample(members, min(SAMPLE_FOR_LARGE, len(members)))
    selected = sorted(selected, key=lambda s: s["slug"])

    tasks = []
    for i, member in enumerate(selected):
        full = superset_by_slug.get(member["slug"], member)
        framing = FRAMINGS[i % len(FRAMINGS)]
        prompt = _to_request(full.get("description", ""), full.get("name", ""), member["slug"], framing)
        tasks.append({
            "id": f"{subset['name']}-t{i + 1:03d}",
            "prompt": prompt,
            "gold_skill": member["slug"],
            "category": "derived-clear-match",
            "rationale": f"Prompt derived from the '{member['slug']}' skill description; "
                         f"that skill is the intended gold match within subset '{subset['name']}'.",
        })

    return {
        "version": f"{subset['name']}-v1",
        "labeling_doc": "../LABELING.md",
        "subset": subset["name"],
        "flavor": subset["flavor"],
        "size": size,
        "seed": seed,
        "notes": "Auto-derived tasks: one natural request per gold skill, templated from the skill's "
                 "own description (name/slug scrubbed). Gold labels are in-subset by construction.",
        "tasks": tasks,
    }


def write_task_set(task_set: dict, out_dir: str | Path = TASKS_DIR) -> Path:
    d = Path(out_dir)
    d.mkdir(parents=True, exist_ok=True)
    p = d / f"{task_set['subset']}.json"
    p.write_text(json.dumps(task_set, indent=2), encoding="utf-8")
    return p


def main() -> int:
    import argparse
    from harness.build_subsets import FLAVORS, SIZES, load_subset

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Auto-derive gold-labeled task sets per subset (task 4).")
    parser.add_argument("--out", default=TASKS_DIR)
    parser.add_argument("--seed", type=int, default=DERIVE_SEED)
    args = parser.parse_args()

    superset = json.loads(Path(SUPERSET_MANIFEST).read_text(encoding="utf-8"))
    by_slug = {s["slug"]: s for s in superset["skills"]}

    for flavor in FLAVORS:
        for size in SIZES:
            name = f"{flavor}-{size}"
            subset = load_subset(name)
            ts = derive_for_subset(subset, by_slug, seed=args.seed)
            p = write_task_set(ts, args.out)
            print(f"{name:22} tasks={len(ts['tasks']):3} -> {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
