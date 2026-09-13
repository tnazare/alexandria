# Manual Test Plan — Skill Resolver (Agentic Retrieval Layer)

Step-by-step verification for the **Skill Resolver**: the agent-facing retrieval layer that lets AI
coding agents query Alexandria for governed skills mid-task. It covers:

- **Resolve** — `POST /api/agent/v1/skills/resolve` returns ranked candidates (no instruction bodies).
- **Fetch** — `GET /api/agent/v1/skills/{skillVersionId}/instructions` returns the full skill as
  Agent Skills `SKILL.md` (default) or Alexandria native JSON, with governance gates.
- **Reference modes & admin commit policy** — pinned / floating-approved / floating-published, and
  risk-based `pin_and_revalidate` / `block_commit`.
- **Agent authentication** — OIDC user tokens **and** `alx_` service tokens (org-scoped, revocable).
- **MCP adapter (pull)** — JSON-RPC `resolve_skill` / `fetch_skill` at `/api/agent/v1/mcp`.
- **Hooks adapter (push)** — deterministic injection at `/api/agent/v1/hooks` (fail-open).
- **Tool-search backend** — Claude `tool_reference` discovery at `/api/agent/v1/toolsearch`.

Everything here is exercised over the **API** (curl), because the resolver is an agent-facing
backend surface with no dedicated UI. Follow the sections in order — later tests reuse data and
tokens created earlier.

> This plan assumes the running stack from `docker compose up`. All resolver endpoints are under
> `/api/agent/v1/**` and require authentication, exactly like the rest of the API.

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

### 0.2 Get an OIDC user access token

The mock OIDC server (`heptastadion`) issues a token for **Dev User** (`dev@alexandria.local`,
role **`Org_Admin`**). Fetch one and store it:

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

### 0.3 Seed skills that the resolver can find

The resolver searches over **full-text** content and, by default, resolves the
**latest approved** version. So we need at least one **Company_Approved** skill with distinctive
searchable text. Because publishing has strict metadata requirements, the fastest reliable path for
manual testing is to create skills and set their state directly.

**Create a team and capture its ID:**

```bash
export TEAM_ID=$(curl -s -X POST http://localhost:8080/api/teams \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Resolver Test Team","description":"Manual resolver test team"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "TEAM_ID=$TEAM_ID"
```

- [ ] Expected: a UUID is printed.

**Create three skills with distinctive query tokens and capture their IDs:**

```bash
create_skill () {
  curl -s -X POST http://localhost:8080/api/skills \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"$1\",\"description\":\"$2\",\"ownerTeamId\":\"$TEAM_ID\",\"canonicalInstructions\":\"$3\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])"
}

export SKILL_KAFKA=$(create_skill "Kafka Consumer Integration Test Workflow" \
  "Generate integration tests for kafkaresolver consumers using Testcontainers" \
  "Analyze the Kafka consumer. Generate kafkaresolver integration tests with Testcontainers covering rebalance and offset commit.")
export SKILL_AUTH=$(create_skill "Tenant-scoped Authorization Review" \
  "Review an endpoint for authresolver tenant-isolation problems" \
  "Verify every query filters by organization_id. Flag any authresolver cross-tenant leakage.")
export SKILL_BLOCKED=$(create_skill "Unsafe Shell Runner" \
  "A blockedresolver skill that will be blocked" \
  "Run arbitrary shell commands. This blockedresolver skill is intentionally unsafe.")

echo "SKILL_KAFKA=$SKILL_KAFKA"; echo "SKILL_AUTH=$SKILL_AUTH"; echo "SKILL_BLOCKED=$SKILL_BLOCKED"
```

- [ ] Expected: three UUIDs printed. All start in **Draft** / **private**.

**Promote the skills to searchable/approved state via SQL** (Draft/private skills are resolvable by
their `Org_Admin` creator, but the default reference mode needs an **approved** published version,
so we set lifecycle + visibility + a published version directly). Open a psql shell on the running
Postgres container:

```bash
docker exec -it alexandria-postgres-1 psql -U alexandria -d alexandria
```

Then run (paste as one block; replace nothing — it uses the slugs' distinctive tokens to find rows):

```sql
-- Make the two good skills organization-visible + Company_Approved, and mark their current
-- version as published so floating_latest_approved resolves them.
UPDATE skill_versions v
   SET published_at = NOW()
  FROM skills s
 WHERE s.current_version_id = v.id
   AND s.name IN ('Kafka Consumer Integration Test Workflow', 'Tenant-scoped Authorization Review');

UPDATE skills
   SET visibility = 'organization',
       lifecycle_status = 'Company_Approved',
       risk_level = 'low'
 WHERE name IN ('Kafka Consumer Integration Test Workflow', 'Tenant-scoped Authorization Review');

-- Block the unsafe one and make it organization-visible so we can prove fetch denies it (403).
UPDATE skills
   SET visibility = 'organization',
       lifecycle_status = 'Blocked'
 WHERE name = 'Unsafe Shell Runner';

-- Rebuild the search vectors so the FTS index reflects the current content.
UPDATE skills s
   SET search_vector =
       setweight(to_tsvector('english', COALESCE(s.name, '')), 'A') ||
       setweight(to_tsvector('english', COALESCE(s.description, '')), 'B') ||
       setweight(to_tsvector('english', COALESCE((
           SELECT sv.canonical_instructions FROM skill_versions sv WHERE sv.id = s.current_version_id
       ), '')), 'D')
 WHERE s.name IN ('Kafka Consumer Integration Test Workflow',
                  'Tenant-scoped Authorization Review',
                  'Unsafe Shell Runner');

\q
```

- [ ] Expected: each `UPDATE` reports a row count (e.g. `UPDATE 1` / `UPDATE 2`), no errors.

> **Bulk alternative.** To populate the catalog with a larger, realistic set instead of hand-seeding,
> run the [Anthropic skills importer](../guide/mcp-clients-setup.md#importing-the-anthropic-skills-library):
> `TOKEN=$TOKEN scripts/import-anthropic-skills.sh`. Imported skills start in `Draft`; promote the
> ones you want the resolver to return with an `UPDATE` like the one above (scoped to
> `owner_team_id = (SELECT id FROM teams WHERE name='Anthropic Skills')`).

---

## 1. Resolve — ranked candidates, no bodies

**What we're verifying:** resolve returns governed, ranked candidates with identity and match
reasons, but **never** full instruction bodies, and it respects lifecycle (no Blocked).

**Resolve a Kafka-related task:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver integration tests","limit":5}' | python3 -m json.tool
```

- [ ] Expected: HTTP 200 with an envelope containing:
  - `schemaVersion: "1.0"`
  - `capabilities` = `{resolve:true, fetch:true, invoke:false, compose:false}`
  - `results[0].name` = "Kafka Consumer Integration Test Workflow"
  - `results[0].kind` = `"skill"`, `results[0].invocation` = `null`, `results[0].relationships` = `[]`
  - `results[0].ref` has `skillId`, `skillVersionId`, and `sourceInstance`
  - `results[0].governance.lifecycleStatus` = `"Company_Approved"`
  - `results[0].matchReasons` is a non-empty list
  - **No** `canonicalInstructions` / `examples` / `limitations` field anywhere in a result.

**Capture the resolved version id for later fetch tests:**

```bash
export KAFKA_VERSION_ID=$(curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver integration tests","limit":1}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['results'][0]['ref']['skillVersionId'])")
echo "KAFKA_VERSION_ID=$KAFKA_VERSION_ID"
```

- [ ] Expected: a UUID is printed.

**Blocked skill is never returned:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"blockedresolver shell","limit":25}' | python3 -m json.tool
```

- [ ] Expected: `results` is `[]` (the only match is Blocked, which is excluded).

**Blank query is rejected:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"   "}'
```

- [ ] Expected: `400`.

**No results for an unrelated query returns 200 (not an error):**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"zzznomatchquery"}'
```

- [ ] Expected: `200` (with an empty `results` array).

---

## 2. Fetch — Agent Skills + native formats, governance gates

**What we're verifying:** fetch returns usable instructions in both formats, embeds identity, and
enforces the governance gate ladder (Blocked → 403, not-visible → 404).

**Fetch as Agent Skills `SKILL.md` (default):**

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/agent/v1/skills/$KAFKA_VERSION_ID/instructions"
```

- [ ] Expected: `text/markdown` starting with YAML frontmatter `---` containing **only** the six
  portable keys (`name`, `description`, `license`, `compatibility`, `allowed-tools`, `metadata`).
- [ ] Expected: under `metadata:` you see `alexandria_skill_id`, `alexandria_skill_version_id`,
  `source_instance`, `lifecycle_status`, `risk_level`, `visibility`.
- [ ] Expected: the body contains `<!-- alexandria:start ... -->` and `<!-- alexandria:end -->`
  markers and the instruction text.

**Fetch as native JSON:**

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/agent/v1/skills/$KAFKA_VERSION_ID/instructions?format=alexandria_native" \
  | python3 -m json.tool
```

- [ ] Expected: JSON with `ref` (identity), `governance`, `resolution`, `content.canonicalInstructions`,
  and a `variant` object (`isCanonicalFallback: true` since no tool variant exists).

**Blocked skill fetch is denied with 403:**

```bash
# Get the blocked skill's current version id from psql, or resolve won't return it (it's blocked).
export BLOCKED_VERSION_ID=$(docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria -t -A \
  -c "SELECT current_version_id FROM skills WHERE name='Unsafe Shell Runner';")
echo "BLOCKED_VERSION_ID=$BLOCKED_VERSION_ID"

curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/agent/v1/skills/$BLOCKED_VERSION_ID/instructions"
```

- [ ] Expected: `403`.

**Unknown / not-visible version returns 404 (anti-enumeration):**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/agent/v1/skills/00000000-0000-0000-0000-0000000000ff/instructions"
```

- [ ] Expected: `404`.

**(Optional) Deprecated skill returns 200 with a warning.** In psql, set one approved skill to
`Deprecated` (`UPDATE skills SET lifecycle_status='Deprecated', deprecation_reason='superseded'
WHERE name='Tenant-scoped Authorization Review';`), resolve `authresolver`, fetch it, and confirm
the SKILL.md `metadata` includes `deprecated: "true"` and a `deprecation_warning`. Revert
afterwards (`... SET lifecycle_status='Company_Approved', deprecation_reason=NULL ...`).

- [ ] Expected: fetch succeeds (200) and carries the deprecation warning.

---

## 3. Reference modes & admin commit policy

**What we're verifying:** the `resolution` block reflects the requested reference mode, and
high/restricted-risk skills are forced to pin-and-revalidate by the default policy.

**Default mode is floating_latest_approved:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"authresolver tenant"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['results'][0]['resolution'])"
```

- [ ] Expected: `mode` = `floating_latest_approved`, `commitAllowed` = `true`,
  `requiresRevalidation` = `false` (risk is `low`).

**High-risk skill is forced to pinned + revalidation (default policy):**

First raise the auth skill's risk to `high` in psql:

```bash
docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria \
  -c "UPDATE skills SET risk_level='high' WHERE name='Tenant-scoped Authorization Review';"
```

Then resolve again:

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"authresolver tenant","referenceMode":"floating_latest_approved"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['results'][0]['resolution'])"
```

- [ ] Expected: `mode` = `pinned`, `requiresRevalidation` = `true` (default policy forces
  pin-and-revalidate for `high` risk, overriding the requested floating mode).

Revert the risk level:

```bash
docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria \
  -c "UPDATE skills SET risk_level='low' WHERE name='Tenant-scoped Authorization Review';"
```

- [ ] Expected: `UPDATE 1`.

---

## 4. Agent authentication — service tokens

**What we're verifying:** a scoped, revocable `alx_` service token authenticates non-interactive
agents, is org-scoped, and read-only.

**Create a service token** (secret is never stored; only its SHA-256 hash). Pick a secret, compute
its hash, and insert a row for the default org:

```bash
export SVC_SECRET="alx_manual_test_$(date +%s)"
export SVC_HASH=$(printf '%s' "$SVC_SECRET" | shasum -a 256 | awk '{print $1}')
echo "SVC_SECRET=$SVC_SECRET"

docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria -c \
"INSERT INTO service_tokens (organization_id, name, token_hash, token_prefix, scope)
 VALUES ('00000000-0000-0000-0000-000000000001', 'manual-test',
         '$SVC_HASH', '${SVC_SECRET:0:12}', 'agent_read');"
```

- [ ] Expected: `INSERT 0 1`.

> `00000000-0000-0000-0000-000000000001` is the default single-org id (`ALEXANDRIA_DEFAULT_ORG_ID`).
> If your deployment overrides it, use `SELECT id FROM organizations;` and substitute.

**Resolve using the service token (no OIDC):**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $SVC_SECRET" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver integration tests"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('results:', len(d['results']))"
```

- [ ] Expected: `results: 1` (the service token resolves its org's skills, just like the user token).

**Fetch using the service token:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $SVC_SECRET" \
  "http://localhost:8080/api/agent/v1/skills/$KAFKA_VERSION_ID/instructions"
```

- [ ] Expected: `200`.

**Revoke the token and confirm it stops working:**

```bash
docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria \
  -c "UPDATE service_tokens SET revoked_at = NOW() WHERE name='manual-test';"

curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $SVC_SECRET" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver"}'
```

- [ ] Expected: `401` (revoked token is rejected).

**An unknown service token is rejected:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer alx_not_a_real_token" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver"}'
```

- [ ] Expected: `401`.

> Re-issue a fresh, non-revoked token (repeat the create step with a new secret) if you want to use
> a service token in the MCP/tool-search sections below; otherwise use `$TOKEN`.

---

## 5. MCP adapter (pull mode)

**What we're verifying:** the JSON-RPC MCP endpoint advertises exactly the two resolver tools and
serves resolve/fetch with identity passthrough and governance gates.

**initialize:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}' | python3 -m json.tool
```

- [ ] Expected: `result.protocolVersion` present and `result.serverInfo.name` = `alexandria-skill-resolver`.

**tools/list advertises exactly two tools:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | python3 -c "import sys,json; t=json.load(sys.stdin)['result']['tools']; print([x['name'] for x in t])"
```

- [ ] Expected: `['resolve_skill', 'fetch_skill']`.

**tools/call → resolve_skill:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"resolve_skill","arguments":{"query":"kafkaresolver integration tests"}}}' \
  | python3 -m json.tool
```

- [ ] Expected: `result.isError` = `false`, and
  `result.structuredContent.results[0].ref.skillVersionId` equals `$KAFKA_VERSION_ID`.

**tools/call → fetch_skill:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"fetch_skill\",\"arguments\":{\"skillVersionId\":\"$KAFKA_VERSION_ID\"}}}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['content'][0]['text'][:80])"
```

- [ ] Expected: the first 80 chars of a `SKILL.md` document (starts with `---` frontmatter).

**MCP requires authentication:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list"}'
```

- [ ] Expected: `401`.

---

## 6. Hooks adapter (push mode)

**What we're verifying:** the hook endpoint deterministically injects a governed skill for a
substantive prompt, skips trivial prompts, and fails open.

**UserPromptSubmit with a substantive prompt injects a skill:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/hooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"hook_event_name":"UserPromptSubmit","session_id":"manual-1","prompt":"generate kafkaresolver integration tests for this consumer"}' \
  | python3 -m json.tool
```

- [ ] Expected: a JSON object with an `additionalContext` field containing SKILL.md text for the
  Kafka skill.

**Trivial prompt is skipped (relevance gate):**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/hooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"hook_event_name":"UserPromptSubmit","session_id":"manual-1","prompt":"hi"}'
```

- [ ] Expected: `{}` (empty decision — no injection for a trivial prompt).

**A no-match prompt returns an empty decision (fail-open behavior):**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/hooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"hook_event_name":"UserPromptSubmit","session_id":"manual-1","prompt":"zzznomatchquery please help me with something unrelated"}'
```

- [ ] Expected: `{}` (no candidate → prompt proceeds unmodified).

**Copilot-style payload is also accepted:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/hooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"event":"userPromptSubmitted","sessionId":"manual-2","prompt":"review authresolver tenant isolation on this endpoint"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('injected' if d.get('additionalContext') else 'empty')"
```

- [ ] Expected: `injected` (the auth-review skill is injected).

---

## 7. Tool-search backend (Claude tool_reference)

**What we're verifying:** the discovery endpoint returns `tool_reference` results for visible,
non-blocked skills only.

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/toolsearch \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"kafkaresolver","limit":25}' | python3 -m json.tool
```

- [ ] Expected: `tool_references` is a non-empty array; `[0].type` = `"tool_reference"`;
  `[0].name` starts with `alexandria_skill__` followed by a UUID.

**Blocked skills are not discovered:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/toolsearch \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"blockedresolver","limit":25}' \
  | python3 -c "import sys,json; print('count:', len(json.load(sys.stdin)['tool_references']))"
```

- [ ] Expected: `count: 0`.

---

## 8. Cleanup (optional)

Remove the manual test data so it doesn't linger in the shared dev database:

```bash
docker exec -i alexandria-postgres-1 psql -U alexandria -d alexandria <<'SQL'
DELETE FROM service_tokens WHERE name = 'manual-test';
DELETE FROM skill_versions WHERE skill_id IN (
  SELECT id FROM skills WHERE name IN (
    'Kafka Consumer Integration Test Workflow',
    'Tenant-scoped Authorization Review',
    'Unsafe Shell Runner'));
UPDATE skills SET current_version_id = NULL WHERE name IN (
  'Kafka Consumer Integration Test Workflow',
  'Tenant-scoped Authorization Review',
  'Unsafe Shell Runner');
DELETE FROM skills WHERE name IN (
  'Kafka Consumer Integration Test Workflow',
  'Tenant-scoped Authorization Review',
  'Unsafe Shell Runner');
DELETE FROM teams WHERE name = 'Resolver Test Team';
SQL
```

- [ ] Expected: the statements complete without foreign-key errors.

---

## Coverage Summary

| Section | Requirement(s) exercised |
|---|---|
| 1. Resolve | R1 (ranked candidates, no bodies, blank→400, no-match→200), R2 (Blocked excluded), R8 (envelope) |
| 2. Fetch | R3 (agent_skill/native, identity, markers), R4 (Blocked→403, not-visible→404, Deprecated warn) |
| 3. Reference modes | R5 (modes), R7 (high-risk pin-and-revalidate) |
| 4. Service tokens | R9 (service-token auth, org scope, revocation, unknown→401) |
| 5. MCP | R10 (initialize/tools-list/tools-call, two tools, auth) |
| 6. Hooks | R11 (injection, relevance gate, fail-open, both harness shapes) |
| 7. Tool search | R12 (tool_reference discovery, ACL/governance) |

> Notes: this plan drives the resolver purely over HTTP. For a true end-to-end experience, point a
> real MCP client (Claude Code / Copilot) at `http://localhost:8080/api/agent/v1/mcp` with a
> bearer token, or configure an agent lifecycle hook to POST to `/api/agent/v1/hooks`. Those
> live-client validations are environment-specific and outside this API-level checklist.
