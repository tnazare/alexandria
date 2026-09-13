"""SKILL.md frontmatter parsing, shared across the harness.

Mirrors the behavior proven in ``scripts/import_anthropic_skills.py``: split the leading ``---``
YAML frontmatter from the markdown body, parse it with PyYAML when available (so block scalars
``>`` / ``|`` are handled), and fall back to a minimal parser otherwise. Kept in one place so the
harvester, corpus builder, and token measurement all parse identically.
"""

from __future__ import annotations

from typing import Tuple


def parse_frontmatter(text: str) -> Tuple[dict, str]:
    """Return ``(frontmatter_dict, body)``. Missing/malformed frontmatter yields ``({}, text)``."""
    if not text.startswith("---"):
        return {}, text
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
    """Fallback: top-level ``key: value`` plus ``>``/``|`` block scalars (no PyYAML dependency)."""
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


def slug_from_path(path: str) -> str:
    """Extract the skill slug from a ``.../<slug>/SKILL.md`` path."""
    parts = [p for p in path.replace("\\", "/").split("/") if p]
    if len(parts) >= 2 and parts[-1].upper() == "SKILL.MD":
        return parts[-2]
    return parts[-1] if parts else path
