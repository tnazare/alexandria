"""Provision the Native_Condition from a built corpus (Requirements 1.2, 1.4).

Lays the size-N corpus into an isolated workspace's ``.claude/skills/`` directory so Claude Code,
launched with that workspace as its CWD, discovers exactly this corpus and nothing else. Verifies
the installed skill count equals N and **halts** if not (Requirement 1.4).

The files placed are byte-identical to the corpus ``SKILL.md`` files, so the Native and Alexandria
conditions serve the same content and only the *mechanism* differs (Requirement 1.1).
"""

from __future__ import annotations

import logging
import shutil
from pathlib import Path

from harness.build_corpus import load_manifest

log = logging.getLogger("harness.provision_native")


class NativeProvisionError(RuntimeError):
    pass


def provision(corpus_dir: str | Path, workspace_dir: str | Path) -> int:
    """Copy the corpus into ``<workspace>/.claude/skills/`` and return the installed skill count.

    Clears any prior ``.claude/skills`` in the workspace first so the corpus is exactly N skills.
    Halts if a corpus file is missing or the final count != N (Requirement 1.4).
    """
    corpus = Path(corpus_dir)
    manifest = load_manifest(corpus / "manifest.json")

    skills_root = Path(workspace_dir) / ".claude" / "skills"
    if skills_root.exists():
        shutil.rmtree(skills_root)
    skills_root.mkdir(parents=True, exist_ok=True)

    installed = 0
    for entry in manifest.skills:
        src = corpus / entry.tier.value / entry.slug / "SKILL.md"
        if not src.is_file():
            raise NativeProvisionError(f"missing corpus file for '{entry.slug}': {src}")
        dest = skills_root / entry.slug / "SKILL.md"
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)
        installed += 1

    found = sum(1 for _ in skills_root.glob("*/SKILL.md"))
    if found != manifest.library_size or installed != manifest.library_size:
        raise NativeProvisionError(
            f"installed {found} native skills but corpus size is {manifest.library_size}; "
            f"aborting (Requirement 1.4)."
        )
    log.info("installed %d skills into %s", found, skills_root)
    return found


def main() -> int:
    import argparse

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Provision the native condition from a corpus.")
    parser.add_argument("--corpus", default="corpus")
    parser.add_argument("--workspace", required=True, help="Isolated workspace dir for Claude Code")
    args = parser.parse_args()

    n = provision(args.corpus, args.workspace)
    print(f"Provisioned native condition: {n} skills under {args.workspace}/.claude/skills/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
