"""Provision the catalog condition: build the resident Skill Catalog text from a corpus.

The catalog condition's "provisioning" is purely producing the resident catalog block that gets
appended to Claude's system prompt — one ``id: name — description`` line per skill, same information
native preloads, but as a compact lookup table the model selects an id from. The skill **id is the
slug**, because that is what the local fetch server (:mod:`harness.catalog_server`) keys on; the
display name is the SKILL.md frontmatter name (falling back to a titleized slug) so the resident
content matches what native shows.

There is no stack to load and no files to copy into a workspace — the bodies are served on demand by
the local fetch server straight from the corpus. So this module is pure text generation plus the
slug->body availability check, and it is fully unit-testable offline.
"""

from __future__ import annotations

from pathlib import Path

from harness.build_corpus import load_manifest
from harness.frontmatter import parse_frontmatter
from harness.measure_tokens import catalog_line
from harness.models import CorpusManifest


class CatalogProvisionError(RuntimeError):
    pass


def _display_name(slug: str, fm: dict, manifest_name: str | None) -> str:
    """Prefer the frontmatter name, then the manifest name, then a titleized slug."""
    name = str(fm.get("name") or manifest_name or "").strip()
    if name:
        return name
    return " ".join(w[:1].upper() + w[1:] for w in slug.replace("-", " ").replace("_", " ").split())


def build_catalog_text(corpus_dir: str | Path, manifest: CorpusManifest | None = None) -> str:
    """Return the resident Skill Catalog block for ``corpus_dir`` (one id/name/description per skill).

    Halts if a corpus body is missing (so a run never advertises a skill it cannot fetch).
    Deterministic: lines follow the manifest's skill order.
    """
    corpus = Path(corpus_dir)
    manifest = manifest or load_manifest(corpus / "manifest.json")
    lines: list[str] = []
    for entry in manifest.skills:
        skill_md = corpus / entry.tier.value / entry.slug / "SKILL.md"
        if not skill_md.is_file():
            raise CatalogProvisionError(f"missing corpus file for '{entry.slug}': {skill_md}")
        fm, _ = parse_frontmatter(skill_md.read_text(encoding="utf-8"))
        description = str(fm.get("description") or "").strip()
        lines.append(catalog_line(entry.slug, _display_name(entry.slug, fm, entry.name), description))
    return "\n".join(lines)


def catalog_system_prompt(directive: str, catalog_text: str) -> str:
    """Compose the condition's appended system prompt: the consult directive + the resident catalog.

    The directive tells the agent to select from the catalog and fetch the chosen id; the catalog is
    the resident block it selects from. Both are appended to Claude's system prompt so selection
    happens over resident text (no retrieval round-trip to skip) — the whole point of the condition.
    """
    directive = " ".join(directive.split()) if directive else ""
    return f"{directive}\n\n## Skill Catalog (id: name — description)\n{catalog_text}".strip()


def catalog_id_to_slug(manifest: CorpusManifest) -> dict[str, str]:
    """Identity map slug->slug: the catalog id *is* the slug, so scoring maps directly."""
    return {e.slug: e.slug for e in manifest.skills}
