# Manual Test Plan — Skill Export, Lifecycle & Search

Step-by-step verification for three Phase 1 milestones:

- **Skill Export** — render a skill into tool-specific formats (copy / download), blocked skills denied, usage events recorded.
- **Skill Lifecycle** — deprecate / block / archive / restore governance transitions, with UI controls and listing/search effects.
- **Skill Search** — full-text search across name/description/tags/instructions, relevance ranking, and faceted filtering.

Each section can be run through the **UI** or the **API** (curl). Both paths are provided. Follow the sections in order — later tests reuse data created earlier.

---

## 0. Environment Setup

### 0.1 Start the stack

```bash
cd /Users/tnazare/code/alexandria
docker compose up --build
```

Wait until all four services are healthy: `postgres`, `heptastadion` (mock OIDC), `backend`, `frontend`.

**Verify backend health:**

```bash
curl -s http://localhost:8080/actuator/health
```

- [ ] Expected: `{"status":"UP"}`

**Verify frontend loads:**

Open `http://localhost:5173` in a browser.

- [ ] Expected: redirected through OIDC login; after login you land on `/admin` (the dev user has role `Org_Admin`).

> The mock OIDC server issues a token for **Dev User** (`dev@alexandria.local`) with role **`Org_Admin`**. This means the dev user passes all governance-authority and visibility checks. Where a test requires a *non-admin* to prove an authorization boundary, that is called out explicitly (see the role note in §2.7).

### 0.2 Get an API access token (for curl-based steps)

The mock OIDC server (`heptastadion`) issues tokens via its token endpoint. Fetch one and store it:

```bash
export TOKEN=$(curl -s -X POST \
  "http://localhost:9000/alexandria/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=alexandria-client" \
  -d "scope=openid" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "${TOKEN:0:20}..."   # sanity check — should print a token prefix
```

- [ ] Expected: a non-empty token prefix is printed.

**Verify the token authenticates:**

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/skills | head -c 200
```

- [ ] Expected: a JSON page object (e.g. `{"content":[...],"page":0,...}`), **not** `401`/`403`.

> If you get `401`, the token expired (1-hour expiry) — re-run the export command in 0.2.

### 0.3 Create baseline data (team + taxonomy + skills)

These are prerequisites for the feature tests. Run via API for speed.

**Create a team and capture its ID:**

```bash
export TEAM_ID=$(curl -s -X POST http://localhost:8080/api/teams \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Platform Team","description":"Manual test team"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "TEAM_ID=$TEAM_ID"
```

- [ ] Expected: a UUID is printed.

**Create taxonomy entries (used by search filters):**

```bash
for pair in "task_type:code-review" "language:python" "framework:django" "tool:cursor"; do
  TYPE=${pair%%:*}; VALUE=${pair##*:}
  curl -s -X POST "http://localhost:8080/api/taxonomy/$TYPE" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"value\":\"$VALUE\"}" >/dev/null
  echo "created $TYPE=$VALUE"
done
```

- [ ] Expected: four "created ..." lines, no errors.

**Create three skills and capture their IDs:**

```bash
create_skill () {
  curl -s -X POST http://localhost:8080/api/skills \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"$1\",\"description\":\"$2\",\"ownerTeamId\":\"$TEAM_ID\",\"canonicalInstructions\":\"$3\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])"
}

export SKILL_A=$(create_skill "Kubernetes Deployment Review" \
  "Reviews Kubernetes manifests for security and best practices" \
  "Analyze the provided Kubernetes manifests. Check resource limits, security context, and readiness probes.")
export SKILL_B=$(create_skill "Python Test Generator" \
  "Generates pytest unit tests for Python modules" \
  "Given a Python module, produce comprehensive pytest tests covering edge cases using the frombulate pattern.")
export SKILL_C=$(create_skill "Legacy Migration Helper" \
  "Assists migrating legacy Django apps" \
  "Guide the migration of a legacy Django application to the current framework version.")

echo "SKILL_A=$SKILL_A"; echo "SKILL_B=$SKILL_B"; echo "SKILL_C=$SKILL_C"
```

- [ ] Expected: three UUIDs printed. All three skills start in **Draft** / **private** visibility.

**Make them discoverable for search/export tests** — publish requires metadata, so for manual testing we set them to `organization` visibility and add a tag/risk via update, then publish `SKILL_A`. (Publish validation requires: ≥1 task_type tag, visibility set, ≥1 supported tool, risk level, applicability, ≥1 example.)

> For the search and export tests below, the skills only need to be **visible** to the dev user. Since the dev user is `Org_Admin`, they can already see all skills in their org regardless of visibility. You can proceed without publishing. Publishing is exercised explicitly in the Lifecycle section.

---

## 1. Skill Export

**What we're verifying:** a skill can be rendered into each of the six formats, blocked skills are refused, and copy vs. download map to the correct usage-event type.

The six export formats (wire values): `generic_markdown`, `github_copilot`, `codex`, `agents_md`, `cursor_rule`, `kiro_steering`.

### 1.1 Export each format (API)

```bash
for fmt in generic_markdown github_copilot codex agents_md cursor_rule kiro_steering; do
  echo "=== $fmt ==="
  curl -s -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/skills/$SKILL_A/export?format=$fmt" | head -5
  echo
done
```

- [ ] Expected: each format returns a non-empty text artifact.
- [ ] Expected: every artifact contains the skill's instruction text ("Analyze the provided Kubernetes manifests...").
- [ ] Expected: `generic_markdown` starts with `# Kubernetes Deployment Review` and includes a `> Risk:` line only if a risk level is set.
- [ ] Expected: `agents_md`, `cursor_rule`, and `kiro_steering` use their format-specific headers (e.g. Cursor rule shows `# Cursor Rule:`).

### 1.2 Response is plain text

```bash
curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=generic_markdown" | grep -i content-type
```

- [ ] Expected: `Content-Type: text/plain;charset=UTF-8`.

### 1.3 Invalid format → 400

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=nonsense"
```

- [ ] Expected: `400`.

### 1.4 Unknown tool → 400

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=generic_markdown&tool=not_a_tool"
```

- [ ] Expected: `400`.

### 1.5 Valid tool variant resolution / canonical fallback

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=cursor_rule&tool=cursor" | head -5
```

- [ ] Expected: `200` with the canonical instructions (no `cursor` variant exists yet, so it falls back to canonical — no error).

### 1.6 Missing skill → 404

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/00000000-0000-0000-0000-000000000000/export?format=generic_markdown"
```

- [ ] Expected: `404`.

### 1.7 Missing version → 404

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=generic_markdown&version=999"
```

- [ ] Expected: `404`.

### 1.8 Source parameter (copy vs download)

```bash
# download (default)
curl -s -o /dev/null -w "download -> %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=generic_markdown&source=download"
# copy
curl -s -o /dev/null -w "copy -> %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_A/export?format=generic_markdown&source=copy"
```

- [ ] Expected: both `200`. (These record `export` and `copy` usage events respectively — verified in 1.10.)

### 1.9 Export via the UI

1. Open `http://localhost:5173/catalog` and click into **Kubernetes Deployment Review**.
2. Locate the **Export & Copy** section.

- [ ] Expected: copy buttons ("Copy generic prompt", "Copy for GitHub Copilot", "Copy for Codex") and download buttons ("Export as AGENTS.md", "Export as Cursor rule", "Export as Kiro steering").
3. Click **Copy for GitHub Copilot**.
- [ ] Expected: the button briefly shows **Copied!**; pasting into a text editor yields the rendered artifact.
4. Click **Export as AGENTS.md**.
- [ ] Expected: a file download is triggered; the button briefly shows **Downloaded!**.

### 1.10 Usage events were recorded

Usage events are written to the `usage_events` table. Verify directly in Postgres:

```bash
docker compose exec postgres psql -U alexandria -d alexandria \
  -c "SELECT event_type, source, export_format, count(*) \
      FROM usage_events GROUP BY 1,2,3 ORDER BY 1,2,3;"
```

- [ ] Expected: rows with `event_type = export` (from downloads) and `event_type = copy` (from copies), with `export_format` values matching the formats you exercised.

---

## 2. Skill Lifecycle (Deprecation & Blocking)

**What we're verifying:** the deprecate / block / archive / restore transitions, their authorization, the legality rules, and the listing/search side-effects.

**Lifecycle endpoints** (all `POST`, return the updated skill):
- `POST /api/skills/{id}/deprecate` — optional body `{"reason":"..."}`
- `POST /api/skills/{id}/block`
- `POST /api/skills/{id}/archive`
- `POST /api/skills/{id}/restore`

**Legality table (mirror of the policy):**

| Current status | deprecate | block | archive | restore |
|---|---|---|---|---|
| Draft / Published / Team_Approved / Company_Approved | ✅ | ✅ | ✅ | ❌ |
| Deprecated | ❌ | ✅ | ✅ | ✅ |
| Archived | ❌ | ✅ | ❌ | ✅ |
| Blocked | ❌ | ❌ | ❌ | ✅ |

### 2.1 Deprecate with a reason

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_A/deprecate" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"reason":"Superseded by the v2 review workflow"}' \
  | python3 -m json.tool | grep -E "lifecycleStatus|deprecationReason"
```

- [ ] Expected: `"lifecycleStatus": "Deprecated"` and `"deprecationReason": "Superseded by the v2 review workflow"`.

### 2.2 Restore returns to the prior active status

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_A/restore" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool | grep -E "lifecycleStatus|deprecationReason"
```

- [ ] Expected: `lifecycleStatus` returns to its pre-deprecation value (e.g. `Draft`); `deprecationReason` is `null`.

### 2.3 Block, then confirm it disappears from listings

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_C/block" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep lifecycleStatus
```

- [ ] Expected: `"lifecycleStatus": "Blocked"`.

```bash
# Blocked skills are excluded from listings — even for admins, even when explicitly filtered
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?status=Blocked" | python3 -c "import sys,json; print('total:', json.load(sys.stdin)['totalElements'])"
```

- [ ] Expected: `total: 0` — Blocked skills never appear in listings.

### 2.4 A blocked skill cannot be exported → 409

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills/$SKILL_C/export?format=generic_markdown"
```

- [ ] Expected: `409` (blocked).

### 2.5 Restore the blocked skill

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_C/restore" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep lifecycleStatus
```

- [ ] Expected: `lifecycleStatus` returns to its prior active value (e.g. `Draft`).

### 2.6 Archive and listing exclusion

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_C/archive" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep lifecycleStatus
```

- [ ] Expected: `"lifecycleStatus": "Archived"`.

```bash
# Default listing excludes archived
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print([s['name'] for s in d['content']])"
# Explicit include shows archived
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?includeArchived=true" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print([s['name'] for s in d['content']])"
```

- [ ] Expected: default listing does **not** include "Legacy Migration Helper"; the `includeArchived=true` listing **does**.

Restore it so later tests aren't affected:

```bash
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_C/restore" -H "Authorization: Bearer $TOKEN" >/dev/null
```

### 2.7 Illegal transitions → 409

```bash
# Restore an active skill (SKILL_B is still Draft) — illegal
curl -s -o /dev/null -w "restore-active -> %{http_code}\n" \
  -X POST "http://localhost:8080/api/skills/$SKILL_B/restore" -H "Authorization: Bearer $TOKEN"

# Block SKILL_B, then try to block again — illegal (already blocked)
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_B/block" -H "Authorization: Bearer $TOKEN" >/dev/null
curl -s -o /dev/null -w "block-blocked -> %{http_code}\n" \
  -X POST "http://localhost:8080/api/skills/$SKILL_B/block" -H "Authorization: Bearer $TOKEN"
# restore it back
curl -s -X POST "http://localhost:8080/api/skills/$SKILL_B/restore" -H "Authorization: Bearer $TOKEN" >/dev/null
```

- [ ] Expected: `restore-active -> 409` and `block-blocked -> 409`.

### 2.8 Authorization boundary (non-admin) — optional

The dev user is `Org_Admin`, so it always passes. To prove the 403 boundary you need a non-governance role (e.g. `Skill_Author`). This requires either a second mock-OIDC token profile with `alexandria_role: Skill_Author` or promoting/demoting a user via the admin API. If you have such a token in `$TOKEN_AUTHOR`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8080/api/skills/$SKILL_A/deprecate" \
  -H "Authorization: Bearer $TOKEN_AUTHOR"
```

- [ ] Expected: `403` (only `Catalog_Admin` / `Org_Admin` may perform lifecycle transitions).

> If you cannot easily obtain a non-admin token, this boundary is already covered by the backend integration tests (`SkillLifecycleIntegrationTest`). Mark as N/A.

### 2.9 Lifecycle controls via the UI

1. Open `http://localhost:5173/catalog/skills` (authoring list) and click into a skill.
2. Locate the **Governance** control panel (visible because the dev user is an admin).

- [ ] Expected: the panel shows only the actions legal for the current status (e.g. a Published skill shows **Deprecate / Block / Archive**, not Restore).
3. Click **Deprecate**, enter a reason, and confirm.
- [ ] Expected: an amber **deprecation warning banner** appears with the reason; the status badge shows **Deprecated**.
4. Click **Block** (confirm the dialog).
- [ ] Expected: a red **blocked indicator** appears; export buttons on the browse detail page for that skill are hidden/disabled.
5. Click **Restore**.
- [ ] Expected: the skill returns to its prior active status; banners clear.

---

## 3. Skill Search

**What we're verifying:** full-text search across name/description/tags/instructions, relevance-ranked ordering, and faceted filters (status, visibility, riskLevel, team, and the four taxonomy dimensions).

> Prerequisite: ensure `SKILL_A`, `SKILL_B`, `SKILL_C` exist and are not blocked/archived (restore them from §2 if needed). Search only returns skills visible to the caller; the dev user (Org_Admin) sees all.

### 3.1 Full-text search by name

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?search=Kubernetes" \
  | python3 -c "import sys,json;print([s['name'] for s in json.load(sys.stdin)['content']])"
```

- [ ] Expected: list includes "Kubernetes Deployment Review".

### 3.2 Search matches instruction content (weight D)

`SKILL_B`'s instructions contain the distinctive word "frombulate", which is not in its name or description.

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?search=frombulate" \
  | python3 -c "import sys,json;print([s['name'] for s in json.load(sys.stdin)['content']])"
```

- [ ] Expected: list includes "Python Test Generator" — proving instructions are indexed.

### 3.3 Search matches tag values (weight C)

First tag `SKILL_A` with the `task_type=code-review` entry via the UI (skill edit page → tags) **or** confirm the tag is applied, then:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?search=code-review" \
  | python3 -c "import sys,json;print([s['name'] for s in json.load(sys.stdin)['content']])"
```

- [ ] Expected: skills tagged `code-review` appear (tag values participate in search).

> If you haven't tagged any skill yet, tag one via the UI: open the skill's **Edit** page, add the `code-review` task-type tag, and save. Tagging triggers a search-vector rebuild automatically.

### 3.4 Relevance ordering (name beats description/instructions)

Create two skills where a rare term appears in different fields, then search:

```bash
export R_NAME=$(create_skill "Orchestration Manager" "A generic skill" "generic instructions here")
export R_DESC=$(create_skill "Unrelated Title" "handles orchestration pipeline tooling" "generic instructions")

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?search=orchestration" \
  | python3 -c "import sys,json;print([s['name'] for s in json.load(sys.stdin)['content']])"
```

- [ ] Expected: **"Orchestration Manager"** (name match, weight A) appears **before** "Unrelated Title" (description match, weight B).

### 3.5 Filter by taxonomy dimension

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?taskType=code-review" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('total:',d['totalElements'])"
```

- [ ] Expected: only skills tagged with `task_type = code-review` are returned (count reflects that subset).

### 3.6 Taxonomy filter is case-insensitive

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?taskType=CODE-REVIEW" \
  | python3 -c "import sys,json;print('upper:',json.load(sys.stdin)['totalElements'])"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?taskType=code-review" \
  | python3 -c "import sys,json;print('lower:',json.load(sys.stdin)['totalElements'])"
```

- [ ] Expected: `upper` and `lower` counts are equal.

### 3.7 Non-existent taxonomy value → empty (not error)

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?taskType=does-not-exist"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?taskType=does-not-exist" \
  | python3 -c "import sys,json;print('total:',json.load(sys.stdin)['totalElements'])"
```

- [ ] Expected: HTTP `200` and `total: 0`.

### 3.8 Exact filters: riskLevel and visibility

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?visibility=private" \
  | python3 -c "import sys,json;print('private:',json.load(sys.stdin)['totalElements'])"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?riskLevel=high" \
  | python3 -c "import sys,json;print('high-risk:',json.load(sys.stdin)['totalElements'])"
```

- [ ] Expected: the `visibility=private` count matches the number of private skills; `riskLevel=high` returns only high-risk skills (may be 0 if none set).

### 3.9 Combined filters use AND semantics

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/skills?search=review&taskType=code-review" \
  | python3 -c "import sys,json;print([s['name'] for s in json.load(sys.stdin)['content']])"
```

- [ ] Expected: only skills matching **both** the text query and the taxonomy filter are returned.

### 3.10 Empty query returns all visible skills (recency order)

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/skills?page=0&size=50" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('total:',d['totalElements'])"
```

- [ ] Expected: all visible, non-blocked, non-archived skills are returned. With no `search` param, ordering is by `updated_at` descending (most recently changed first).

### 3.11 Search & filters via the UI (browse catalog)

1. Open `http://localhost:5173/catalog`.
2. Type `Kubernetes` in the search box.
- [ ] Expected: the results narrow (after a ~300ms debounce) to matching skills; the URL updates with `?q=Kubernetes`.
3. Open the **Filters** panel. Confirm the **Task Type / Language / Framework / Tool** dropdowns are populated from your taxonomy entries (`code-review`, `python`, `django`, `cursor`) — **not** hardcoded values.
- [ ] Expected: dropdown options reflect the taxonomy you created in §0.3.
4. Select a **Task Type** filter.
- [ ] Expected: results narrow; the URL updates with `taskType=...`.
5. Copy the URL, open it in a new tab.
- [ ] Expected: the search term and filter selections are restored from the URL (deep-linkable state).
6. Clear all filters.
- [ ] Expected: results reset to the full catalog.

### 3.12 Search & filters via the UI (authoring list)

1. Open `http://localhost:5173/catalog/skills`.
- [ ] Expected: the same filter dimensions are available (search, status, visibility, risk level, team, and the four taxonomy dropdowns), consistent with the browse catalog.
2. Apply a status filter (e.g. `Draft`).
- [ ] Expected: the result count updates and the URL persists the filter.

---

## 4. Cleanup (optional)

Stop the stack and remove volumes to reset all data:

```bash
docker compose down -v
```

- [ ] Expected: containers stopped and the `postgres_data` volume removed. A fresh `docker compose up` starts from an empty database.

---

## Results Summary

| Section | Pass | Notes |
|---|---|---|
| 0. Environment setup | ☐ | |
| 1. Skill Export | ☐ | |
| 2. Skill Lifecycle | ☐ | |
| 3. Skill Search | ☐ | |

**Tester:** _______________  **Date:** _______________  **Build / commit:** _______________
