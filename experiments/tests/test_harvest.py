"""Unit tests for the harvester's pure parts: frontmatter parity + skip-on-malformed.

Network-free: exercises ``parse_frontmatter`` and ``build_skill`` directly (Requirement 9.3).
"""

from __future__ import annotations

from harness.frontmatter import parse_frontmatter, slug_from_path
from harness.harvest import Source, build_skill, sha256_hex
from harness.models import Tier

REAL_SOURCE = Source(
    kind="github_repo", owner_repo="anthropics/skills", ref="deadbeef",
    subpath="skills", tier=Tier.REAL, license="UNKNOWN",
)

SIMPLE = """---
name: pdf
description: Work with PDF files.
license: Proprietary
---
# PDF Processing

Body here.
"""

BLOCK_SCALAR = """---
name: academy-guide
description: >
  Stop and check this skill before finishing any reply about how to use Claude.
  It recommends matching courses and tutorials.
---
# Academy Guide

Body.
"""

NO_NAME = """---
description: has no name
---
# Something
Body.
"""

EMPTY_BODY = """---
name: emptybody
description: no body follows
---
"""

NO_FRONTMATTER = """# Just a heading

No frontmatter at all.
"""


class TestFrontmatterParity:
    def test_simple_frontmatter(self):
        fm, body = parse_frontmatter(SIMPLE)
        assert fm["name"] == "pdf"
        assert fm["description"] == "Work with PDF files."
        assert body.startswith("# PDF Processing")

    def test_block_scalar_description_is_joined(self):
        fm, _ = parse_frontmatter(BLOCK_SCALAR)
        assert fm["name"] == "academy-guide"
        # The '>' block scalar must fold into a single non-empty string (importer parity).
        assert "Stop and check this skill" in fm["description"]
        assert "matching courses" in fm["description"]
        assert "\n" not in fm["description"].strip()

    def test_no_frontmatter_returns_empty_dict(self):
        fm, body = parse_frontmatter(NO_FRONTMATTER)
        assert fm == {}
        assert body == NO_FRONTMATTER


class TestSlug:
    def test_slug_from_skill_path(self):
        assert slug_from_path("skills/pdf/SKILL.md") == "pdf"
        assert slug_from_path("a/b/mcp-builder/SKILL.md") == "mcp-builder"


class TestBuildSkillSkips:
    def test_builds_a_valid_skill_with_provenance(self):
        s = build_skill(SIMPLE, source=REAL_SOURCE, path="skills/pdf/SKILL.md",
                        source_url="https://raw/pdf")
        assert s is not None
        assert s.slug == "pdf"
        assert s.name == "pdf"
        assert s.tier == Tier.REAL
        assert s.ref == "deadbeef"
        assert s.source_url == "https://raw/pdf"
        # Frontmatter license overrides the source-level default.
        assert s.license == "Proprietary"
        assert s.sha256 == sha256_hex(SIMPLE.strip())

    def test_falls_back_to_source_license_when_absent(self):
        s = build_skill(BLOCK_SCALAR, source=REAL_SOURCE, path="skills/academy-guide/SKILL.md",
                        source_url="https://raw/academy")
        assert s is not None
        assert s.license == "UNKNOWN"  # no license in frontmatter -> source-level

    def test_skips_skill_without_name(self):
        assert build_skill(NO_NAME, source=REAL_SOURCE, path="skills/x/SKILL.md",
                           source_url="u") is None

    def test_skips_skill_with_empty_body(self):
        assert build_skill(EMPTY_BODY, source=REAL_SOURCE, path="skills/emptybody/SKILL.md",
                           source_url="u") is None

    def test_skips_excluded_slug(self):
        src = Source(kind="github_repo", owner_repo="o/r", ref="r", subpath="skills",
                     tier=Tier.REAL, license="MIT", exclude_slugs=("pdf",))
        assert build_skill(SIMPLE, source=src, path="skills/pdf/SKILL.md", source_url="u") is None
