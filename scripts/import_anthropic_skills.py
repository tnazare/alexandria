#!/usr/bin/env python3
"""Import the Anthropic skills library (github.com/anthropics/skills) into Alexandria.

Each ``skills/<slug>/SKILL.md`` in the source repo becomes one Alexandria skill:

* ``name``                  <- the slug, Title Cased (e.g. ``mcp-builder`` -> "Mcp Builder")
* ``description``           <- the SKILL.md frontmatter ``description`` (truncated to 2000 chars;
                               falls back to a generated line if the frontmatter has none)
* ``canonicalInstructions`` <- the full raw SKILL.md content (truncated to 100000 chars)

Skills are created under a single owner team (created if absent). Created skills start in Draft;
promote the ones you want the resolver to return to Company_Approved / organization afterwards.

Auth: pass an Org_Admin bearer token via ``--token`` or the ``TOKEN`` environment variable.

This script uses only the Python standard library plus PyYAML. If PyYAML is unavailable it falls
back to a minimal frontmatter parser that still handles ``>``/``|`` block scalars.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

# --- constants -------------------------------------------------------------------------------

DEFAULT_HOST = "http://localhost:8080"
DEFAULT_TEAM = "Anthropic Skills"
SOURCE_REPO = "anthropics/skills"
SOURCE_REF = "main"
GITHUB_TREE_URL = f"https://api.github.com/repos/{SOURCE_REPO}/git/trees/{SOURCE_REF}?recursive=1"
RAW_BASE = f"https://raw.githubusercontent.com/{SOURCE_REPO}/{SOURCE_REF}"

NAME_MAX = 200
NAME_MIN = 3
DESCRIPTION_MAX = 2000
INSTRUCTIONS_MAX = 100000

TIMEOUT = 30


# --- HTTP helpers ----------------------------------------------------------------------------


def _get(url: str, token: str | None = None) -> bytes:
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read()


def _post_json(url: str, token: str, payload: dict) -> tuple[int, dict | str]:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail)
        except json.JSONDecodeError:
            pass
        return e.code, detail


# --- frontmatter parsing ---------------------------------------------------------------------


def parse_frontmatter(text: str) -> tuple[dict, str]:
    """Return (frontmatter_dict, body). Missing frontmatter yields ({}, text)."""
    if not text.startswith("---"):
        return {}, text
    # Split on the closing '---' fence.
    parts = text.split("\n---", 1)
    if len(parts) != 2:
        return {}, text
    fm_raw = parts[0][len("---"):].lstrip("\n")
    body = parts[1].lstrip("\n")

    try:
        import yaml  # type: ignore

        data = yaml.safe_load(fm_raw) or {}
        if isinstance(data, dict):
            return data, body
    except Exception:
        pass
    return _parse_frontmatter_minimal(fm_raw), body


def _parse_frontmatter_minimal(fm_raw: str) -> dict:
    """Fallback frontmatter parser: top-level ``key: value`` plus ``>``/``|`` block scalars."""
    data: dict[str, str] = {}
    lines = fm_raw.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line or line.startswith("#") or ":" not in line:
            i += 1
            continue
        key, _, rest = line.partition(":")
        key = key.strip()
        rest = rest.strip()
        if rest in (">", "|", ">-", "|-", ">+", "|+"):
            # Gather the indented block that follows.
            block: list[str] = []
            i += 1
            while i < len(lines) and (lines[i].startswith((" ", "\t")) or lines[i] == ""):
                block.append(lines[i].strip())
                i += 1
            data[key] = " ".join(b for b in block if b).strip()
        else:
            data[key] = rest.strip().strip('"').strip("'")
            i += 1
    return data


# --- transformation --------------------------------------------------------------------------


def titleize(slug: str) -> str:
    name = slug.replace("-", " ").replace("_", " ").strip()
    name = " ".join(w[:1].upper() + w[1:] for w in name.split())
    if len(name) < NAME_MIN:
        name = f"{name} Skill".strip()
    return name[:NAME_MAX]


def build_skill_payload(slug: str, raw: str, team_id: str) -> dict:
    fm, _ = parse_frontmatter(raw)
    description = str(fm.get("description") or "").strip()
    if not description:
        description = f"Anthropic skill '{slug}' imported from {SOURCE_REPO}."
    description = description[:DESCRIPTION_MAX]

    instructions = raw.strip()[:INSTRUCTIONS_MAX]
    if not instructions:
        instructions = f"# {titleize(slug)}\n\n(Imported from {SOURCE_REPO}/{slug}.)"

    return {
        "name": titleize(slug),
        "description": description,
        "ownerTeamId": team_id,
        "canonicalInstructions": instructions,
    }


# --- discovery -------------------------------------------------------------------------------


def list_skill_paths() -> list[str]:
    tree = json.loads(_get(GITHUB_TREE_URL).decode("utf-8"))
    if "tree" not in tree:
        raise RuntimeError(f"Unexpected GitHub tree response: {tree}")
    paths = [
        t["path"]
        for t in tree["tree"]
        if t["path"].startswith("skills/") and t["path"].endswith("/SKILL.md")
    ]
    return sorted(paths)


def slug_from_path(path: str) -> str:
    # skills/<slug>/SKILL.md
    return path.split("/")[1]


# --- Alexandria calls ------------------------------------------------------------------------


def ensure_team(host: str, token: str, team_name: str, dry_run: bool) -> str:
    """Return the id of the owner team, creating it if needed."""
    # Try to find an existing team by name first.
    try:
        raw = _get(f"{host}/api/teams?size=200", token).decode("utf-8")
        page = json.loads(raw)
        for team in page.get("content", []):
            if team.get("name") == team_name:
                return team["id"]
    except urllib.error.HTTPError as e:
        print(f"  ! could not list teams ({e.code}); will attempt to create", file=sys.stderr)

    if dry_run:
        return "00000000-0000-0000-0000-000000000000"

    status, resp = _post_json(
        f"{host}/api/teams",
        token,
        {"name": team_name, "description": f"Skills imported from {SOURCE_REPO}"},
    )
    if status in (200, 201) and isinstance(resp, dict) and "id" in resp:
        return resp["id"]
    # A conflict means the team already exists; re-fetch to get its id.
    if status == 409:
        raw = _get(f"{host}/api/teams?size=200", token).decode("utf-8")
        for team in json.loads(raw).get("content", []):
            if team.get("name") == team_name:
                return team["id"]
    raise RuntimeError(f"Failed to create team (HTTP {status}): {resp}")


def create_skill(host: str, token: str, payload: dict) -> tuple[int, dict | str]:
    return _post_json(f"{host}/api/skills", token, payload)


# --- main ------------------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Import Anthropic skills into Alexandria via the REST API."
    )
    parser.add_argument("--host", default=os.environ.get("ALEXANDRIA_HOST", DEFAULT_HOST),
                        help=f"Alexandria base URL (default: {DEFAULT_HOST})")
    parser.add_argument("--token", default=os.environ.get("TOKEN"),
                        help="Org_Admin bearer token (or set TOKEN env var)")
    parser.add_argument("--team", default=DEFAULT_TEAM,
                        help=f"Owner team name (default: '{DEFAULT_TEAM}')")
    parser.add_argument("--only", default=None,
                        help="Comma-separated slugs to import (default: all)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Show what would be imported without calling the API")
    args = parser.parse_args()

    if not args.token and not args.dry_run:
        print("ERROR: no token. Pass --token or set TOKEN (see docs 0.2).", file=sys.stderr)
        return 2

    only = {s.strip() for s in args.only.split(",")} if args.only else None

    print(f"Discovering skills in {SOURCE_REPO}@{SOURCE_REF} ...")
    try:
        paths = list_skill_paths()
    except Exception as e:  # noqa: BLE001
        print(f"ERROR: failed to list source skills: {e}", file=sys.stderr)
        return 1
    if only:
        paths = [p for p in paths if slug_from_path(p) in only]
    print(f"Found {len(paths)} skill(s).")

    team_id = None
    if not args.dry_run:
        print(f"Resolving owner team '{args.team}' ...")
        team_id = ensure_team(args.host, args.token, args.team, args.dry_run)
        print(f"  team id: {team_id}")
    else:
        team_id = "<dry-run-team-id>"

    created = 0
    failed = 0
    for path in paths:
        slug = slug_from_path(path)
        try:
            raw = _get(f"{RAW_BASE}/{path}").decode("utf-8")
        except Exception as e:  # noqa: BLE001
            print(f"  x {slug}: failed to fetch source ({e})", file=sys.stderr)
            failed += 1
            continue

        payload = build_skill_payload(slug, raw, team_id)

        if args.dry_run:
            print(f"  - {slug} -> name='{payload['name']}', "
                  f"desc={len(payload['description'])} chars, "
                  f"instructions={len(payload['canonicalInstructions'])} chars")
            continue

        status, resp = create_skill(args.host, args.token, payload)
        if status in (200, 201) and isinstance(resp, dict):
            print(f"  + {slug} -> {resp.get('id')} ({payload['name']})")
            created += 1
        else:
            print(f"  x {slug}: HTTP {status}: {resp}", file=sys.stderr)
            failed += 1

    print()
    if args.dry_run:
        print(f"Dry run complete: {len(paths)} skill(s) would be imported.")
    else:
        print(f"Done: {created} created, {failed} failed.")
        if created:
            print("Created skills start in Draft. Promote the ones you want the resolver to "
                  "return to Company_Approved / organization (see the resolver manual test, 0.3).")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
