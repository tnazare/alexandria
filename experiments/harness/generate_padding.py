"""Deterministic Padding_Tier generator (Requirements 2.2, 2.4, 9.4, 9.5).

Produces realistic **variant** skills: the same concept as a real skill, re-expressed for a
different language/framework/technology (e.g. ``pdf-processing-rust-lopdf``). These scale
Library_Size to stress the size-dependence of token cost and retrieval, and — because they are
plausible — act as *hard distractors* that make accuracy a real test.

Guarantees:
- **Deterministic**: a given ``(target_n, seed)`` produces the same skills every run (Requirement
  2.4, 9.5). Generation is not LLM-based; it is a seeded walk over a fixed concept × stack matrix.
- **Never a Gold_Skill**: generated slugs never collide with reserved (real/distractor) slugs, so a
  padding skill can never be a correct answer (Requirement 9.4).
- **Realistic mass**: bodies are templated to a representative size so padding contributes real
  token mass, not empty filler.
"""

from __future__ import annotations

import hashlib
import random
from typing import Iterable

from harness.models import GeneratedSkill

# Concepts intentionally overlap with the real Anthropic skills' domains so the variants read as
# plausible near-matches (hard distractors), forcing gold labels to pin the exact real slug.
CONCEPTS: list[tuple[str, str]] = [
    ("pdf-processing", "read, merge, split, and fill PDF documents"),
    ("spreadsheet-generation", "build multi-sheet spreadsheets with formulas and charts"),
    ("document-authoring", "generate formatted word-processor documents"),
    ("presentation-building", "assemble slide decks from structured content"),
    ("web-scraping", "extract structured data from web pages"),
    ("api-client", "call and paginate a REST API"),
    ("unit-testing", "generate and run unit tests"),
    ("integration-testing", "write integration tests with containers"),
    ("data-visualization", "render charts from tabular data"),
    ("image-processing", "resize, convert, and annotate images"),
    ("markdown-authoring", "produce and lint structured markdown"),
    ("mcp-server", "build a Model Context Protocol server"),
    ("cli-tooling", "build a command-line tool with subcommands"),
    ("schema-validation", "validate payloads against a schema"),
    ("log-analysis", "parse and summarize application logs"),
    ("csv-etl", "transform CSV data between shapes"),
    ("auth-integration", "integrate an OAuth/OIDC authentication flow"),
    ("db-migration", "author and apply database migrations"),
    ("message-queue", "publish and consume messages on a queue"),
    ("caching-layer", "add a read-through cache in front of a datastore"),
    ("email-templating", "render and send templated transactional email"),
    ("pdf-forms", "detect and fill interactive PDF form fields"),
]

# Language/framework pairs. Each (concept, language, framework) yields one distinct variant skill.
# The matrix (len(CONCEPTS) x len(STACKS)) must comfortably exceed the largest Library_Size (500),
# leaving headroom after reserved (real/distractor) slugs are excluded.
STACKS: list[tuple[str, str]] = [
    ("python", "stdlib"),
    ("python", "pandas"),
    ("python", "fastapi"),
    ("python", "django"),
    ("node", "express"),
    ("node", "nest"),
    ("typescript", "deno"),
    ("typescript", "bun"),
    ("rust", "tokio"),
    ("rust", "actix"),
    ("go", "stdlib"),
    ("go", "gin"),
    ("java", "spring"),
    ("java", "quarkus"),
    ("ruby", "rails"),
    ("ruby", "sinatra"),
    ("csharp", "dotnet"),
    ("csharp", "aspnet"),
    ("php", "laravel"),
    ("php", "symfony"),
    ("kotlin", "ktor"),
    ("kotlin", "spring"),
    ("swift", "vapor"),
    ("scala", "akka"),
    ("elixir", "phoenix"),
    ("clojure", "ring"),
    ("cpp", "boost"),
    ("dart", "flutter"),
    ("haskell", "servant"),
    ("perl", "mojolicious"),
]

_BODY_TEMPLATE = """# {title}

## Overview
This skill explains how to {desc} using {language} with {framework}. It is a technology-specific
variant intended for tasks that explicitly target the {language}/{framework} stack.

## When to use
Use this when the task names {language} and {framework} for {concept_words}. For other stacks,
prefer the variant matching that stack.

## Quick start
```{language}
// {language}/{framework}: {desc}
// 1. set up the {framework} entry point
// 2. load the input
// 3. perform the {concept_words} operation
// 4. write the output
```

## Steps
1. Install the {framework} dependencies for {language}.
2. Parse the input according to the task.
3. Apply the {concept_words} transformation.
4. Validate the result and handle errors.
5. Emit the output in the requested format.

## Notes
This is a generated variant skill used to scale the benchmark library. It is never a gold answer.
"""


def _title(concept: str, language: str, framework: str) -> str:
    words = concept.replace("-", " ").title()
    return f"{words} ({language.title()}/{framework})"


def _slug(concept: str, language: str, framework: str) -> str:
    return f"{concept}-{language}-{framework}".lower()


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _all_combinations() -> Iterable[tuple[str, str, str, str]]:
    """Yield (concept, concept_desc, language, framework) for the full matrix, concept-major."""
    for concept, desc in CONCEPTS:
        for language, framework in STACKS:
            yield concept, desc, language, framework


def generate(target_n: int, seed: int, reserved_slugs: set[str] | None = None) -> list[GeneratedSkill]:
    """Return exactly ``target_n`` variant skills, deterministically for ``(target_n, seed)``.

    The full concept × stack matrix is shuffled with ``seed`` and walked in that fixed order; any
    slug colliding with ``reserved_slugs`` (real/distractor) is skipped. Raises if the matrix cannot
    supply enough unique, non-reserved slugs.
    """
    reserved = reserved_slugs or set()
    combos = list(_all_combinations())
    rng = random.Random(seed)
    rng.shuffle(combos)

    out: list[GeneratedSkill] = []
    used: set[str] = set()
    for concept, desc, language, framework in combos:
        if len(out) >= target_n:
            break
        slug = _slug(concept, language, framework)
        if slug in reserved or slug in used:
            continue
        used.add(slug)
        title = _title(concept, language, framework)
        concept_words = concept.replace("-", " ")
        body = _BODY_TEMPLATE.format(
            title=title, desc=desc, language=language, framework=framework,
            concept_words=concept_words,
        )
        description = (f"Variant skill: {concept_words} using {language} with {framework}. "
                       f"Use only when the task explicitly targets this stack.")
        full = f"---\nname: {slug}\ndescription: {description}\n---\n{body}"
        out.append(GeneratedSkill(
            slug=slug, name=title, description=description, body=full,
            concept=concept, language=language, framework=framework, sha256=_sha(full),
        ))

    if len(out) < target_n:
        raise ValueError(
            f"Padding matrix exhausted: need {target_n} variants but only {len(out)} unique "
            f"non-reserved slugs available (matrix = {len(CONCEPTS)}x{len(STACKS)} = "
            f"{len(CONCEPTS) * len(STACKS)}). Extend CONCEPTS/STACKS."
        )
    return out


def max_capacity() -> int:
    """Maximum number of distinct variants the current matrix can produce."""
    return len(CONCEPTS) * len(STACKS)
