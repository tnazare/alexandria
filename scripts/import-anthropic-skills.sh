#!/usr/bin/env bash
#
# Import the Anthropic skills library (github.com/anthropics/skills) into Alexandria.
#
# Thin wrapper around scripts/import_anthropic_skills.py. Requires python3 and an Org_Admin bearer
# token in the TOKEN environment variable (see docs/manual-testing/mcp-clients-manual-test.md, 0.2).
#
# Usage:
#   export TOKEN=...            # Org_Admin OIDC token
#   scripts/import-anthropic-skills.sh                 # import all skills
#   scripts/import-anthropic-skills.sh --dry-run       # preview without calling the API
#   scripts/import-anthropic-skills.sh --only pdf,xlsx # import a subset
#   scripts/import-anthropic-skills.sh --host http://localhost:8080 --team "Anthropic Skills"
#
# All flags are passed straight through to the Python importer; run with --help for the full list.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required but not found on PATH." >&2
  exit 1
fi

exec python3 "${SCRIPT_DIR}/import_anthropic_skills.py" "$@"
