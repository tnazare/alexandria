"""Harvester: collect genuine SKILL.md skills with provenance (Requirements 9.2, 9.3, 9.8).

Reads ``config/sources.yaml`` (pinned sources), walks each repo tree at its pinned commit, takes
every ``**/SKILL.md`` under the configured subpath, parses frontmatter, and records provenance
(source URL, ref, license, sha256) per skill. Sources it cannot fetch and skills it cannot parse are
**skipped and logged** — never silently included (Requirement 9.3). Skills are de-duplicated by
``(name, sha256)``.

Output is written under ``corpus/real/<slug>/SKILL.md`` and ``corpus/distractor/<slug>/SKILL.md``
plus a returned list of :class:`HarvestedSkill` the corpus builder consumes.

Network access uses ``httpx`` against the GitHub REST + raw endpoints. The functions that parse and
assemble skills are pure and unit-tested without the network.
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from harness.frontmatter import parse_frontmatter, slug_from_path
from harness.models import HarvestedSkill, Tier

log = logging.getLogger("harness.harvest")

GITHUB_TREE = "https://api.github.com/repos/{owner_repo}/git/trees/{ref}?recursive=1"
RAW_BASE = "https://raw.githubusercontent.com/{owner_repo}/{ref}/{path}"


@dataclass(frozen=True)
class Source:
    kind: str
    owner_repo: str
    ref: str
    subpath: str
    tier: Tier
    license: str = "UNKNOWN"
    exclude_slugs: tuple[str, ...] = ()


def load_sources(path: str | Path) -> list[Source]:
    import yaml  # PyYAML is a pinned dependency

    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    sources = []
    for raw in data.get("sources", []):
        sources.append(Source(
            kind=str(raw["kind"]),
            owner_repo=str(raw["owner_repo"]),
            ref=str(raw["ref"]),
            subpath=str(raw.get("subpath", "")).strip("/"),
            tier=Tier(str(raw.get("tier", "real"))),
            license=str(raw.get("license", "UNKNOWN")),
            exclude_slugs=tuple(raw.get("exclude_slugs", []) or ()),
        ))
    return sources


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_skill(raw_text: str, *, source: Source, path: str, source_url: str) -> Optional[HarvestedSkill]:
    """Parse one SKILL.md into a HarvestedSkill, or return None if it is malformed.

    A skill is malformed (and skipped) if it has no frontmatter ``name`` or an empty body. Pure and
    network-free so it can be unit-tested directly (Requirement 9.3).
    """
    slug = slug_from_path(path)
    if slug in source.exclude_slugs:
        log.info("skip %s: excluded by source config", slug)
        return None

    fm, body = parse_frontmatter(raw_text)
    name = str(fm.get("name") or "").strip()
    description = str(fm.get("description") or "").strip()

    if not name:
        log.warning("skip %s (%s): no frontmatter 'name'", slug, source_url)
        return None
    if not body.strip():
        log.warning("skip %s (%s): empty body", slug, source_url)
        return None

    # A skill may declare its own license in frontmatter; else use the source-level license.
    license_ = str(fm.get("license") or "").strip() or source.license or "UNKNOWN"

    return HarvestedSkill(
        slug=slug,
        name=name,
        description=description,
        body=raw_text.strip(),
        tier=source.tier,
        source_url=source_url,
        ref=source.ref,
        license=license_,
        sha256=sha256_hex(raw_text.strip()),
    )


def _list_skill_paths(client, source: Source) -> list[str]:
    url = GITHUB_TREE.format(owner_repo=source.owner_repo, ref=source.ref)
    resp = client.get(url, timeout=30.0)
    resp.raise_for_status()
    tree = resp.json()
    if "tree" not in tree:
        raise RuntimeError(f"unexpected tree response for {source.owner_repo}: {tree.get('message')}")
    prefix = f"{source.subpath}/" if source.subpath else ""
    return sorted(
        t["path"] for t in tree["tree"]
        if t["path"].endswith("/SKILL.md") and t["path"].startswith(prefix)
    )


def harvest(sources: list[Source], *, client=None) -> list[HarvestedSkill]:
    """Fetch all skills from the given sources, de-duplicated by (name, sha256).

    Skips-and-logs any source that fails to list and any skill that fails to fetch or parse.
    """
    import httpx

    own_client = client is None
    client = client or httpx.Client(follow_redirects=True)
    seen: set[tuple[str, str]] = set()
    out: list[HarvestedSkill] = []
    try:
        for source in sources:
            try:
                paths = _list_skill_paths(client, source)
            except Exception as e:  # noqa: BLE001 - skip-and-log a bad source
                log.warning("skip source %s@%s: %s", source.owner_repo, source.ref, e)
                continue
            for path in paths:
                raw_url = RAW_BASE.format(owner_repo=source.owner_repo, ref=source.ref, path=path)
                try:
                    r = client.get(raw_url, timeout=30.0)
                    r.raise_for_status()
                except Exception as e:  # noqa: BLE001
                    log.warning("skip %s: fetch failed: %s", raw_url, e)
                    continue
                skill = build_skill(r.text, source=source, path=path, source_url=raw_url)
                if skill is None:
                    continue
                key = (skill.name, skill.sha256)
                if key in seen:
                    log.info("dedupe %s: already harvested (name,sha256)", skill.slug)
                    continue
                seen.add(key)
                out.append(skill)
    finally:
        if own_client:
            client.close()
    return out


def write_corpus_files(skills: list[HarvestedSkill], corpus_dir: str | Path) -> None:
    """Write each harvested skill to ``<corpus_dir>/<tier>/<slug>/SKILL.md``."""
    base = Path(corpus_dir)
    for s in skills:
        dest = base / s.tier.value / s.slug / "SKILL.md"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(s.body + "\n", encoding="utf-8")


def main() -> int:
    import argparse
    import json

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Harvest Real/Distractor tier skills with provenance.")
    parser.add_argument("--sources", default="config/sources.yaml")
    parser.add_argument("--corpus", default="corpus")
    args = parser.parse_args()

    sources = load_sources(args.sources)
    skills = harvest(sources)
    write_corpus_files(skills, args.corpus)

    # Emit a provenance sidecar for auditing (the full manifest is written by build_corpus).
    prov = [
        {"slug": s.slug, "tier": s.tier.value, "name": s.name, "source_url": s.source_url,
         "ref": s.ref, "license": s.license, "sha256": s.sha256}
        for s in skills
    ]
    Path(args.corpus).mkdir(parents=True, exist_ok=True)
    Path(args.corpus, "harvest_provenance.json").write_text(
        json.dumps(prov, indent=2), encoding="utf-8")
    by_tier: dict[str, int] = {}
    for s in skills:
        by_tier[s.tier.value] = by_tier.get(s.tier.value, 0) + 1
    print(f"Harvested {len(skills)} skill(s): {by_tier}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
