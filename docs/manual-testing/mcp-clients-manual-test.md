# Manual Test Plan — MCP Clients (Claude Code / Copilot / Kiro)

Step-by-step verification that a real MCP client can connect to Alexandria's Skill Resolver over the
Streamable HTTP transport, authenticate with a minted **service token**, and call the `resolve_skill`
/ `fetch_skill` tools. It covers:

- **Minting a service token** via the admin API (`POST /api/agent/v1/service-tokens`) — no
  hand-written SQL.
- **Transport sanity checks** against `POST /api/agent/v1/mcp` with raw JSON-RPC.
- **Wiring Claude Code** (primary), plus config snippets for **GitHub Copilot (VS Code)** and **Kiro**.
- **Revocation** — proving a revoked token immediately stops authenticating.

Everything is exercised against the local `docker compose` stack. Follow the sections in order —
later steps reuse tokens created earlier.

> Companion docs: [Skill Resolver manual test](./skill-resolver-manual-test.md) for the underlying
> resolve/fetch API, and the [Connecting MCP Clients guide](../guide/mcp-clients-setup.md) for the
> production (HTTPS) client configuration.

---

## 0. Environment Setup

### 0.1 Start the stack

```bash
cd /Users/tnazare/code/alexandria
docker compose up --build
```

Wait until `postgres`, `heptastadion` (mock OIDC), `backend`, and `frontend` are healthy.

**Verify backend health:**

```bash
curl -s http://localhost:8080/actuator/health
```

- [ ] Expected: `{"status":"UP"}`

### 0.2 Get an Org_Admin OIDC token

The mock OIDC server (`heptastadion`) issues a token for **Dev User** (`dev@alexandria.local`, role
**`Org_Admin`**). This server **requires client authentication**, so a `client_secret` must be sent
(the mock accepts any value):

```bash
export TOKEN=$(curl -s -X POST "http://localhost:9000/alexandria/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=alexandria-client" \
  -d "client_secret=any" \
  -d "scope=openid" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "${TOKEN:0:20}..."   # sanity check — should print a token prefix
```

- [ ] Expected: a non-empty token prefix is printed.

> If you get `KeyError: 'access_token'`, the token request failed. The most common cause is omitting
> `client_secret` — this mock returns `invalid_client: missing client authentication` without it.
> Re-run with `-d "client_secret=any"` as above.

**Verify the token authenticates and carries Org_Admin:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/agent/v1/service-tokens
```

- [ ] Expected: `200` (an `Org_Admin` can list service tokens). A `403` means the token's role
  claim isn't `Org_Admin`; a `401` means it expired (1-hour expiry) — re-run 0.2.

---

## 1. Mint a service token via the admin API

**What we're verifying:** an `Org_Admin` can mint a scoped `alx_` credential through the API, the
secret is revealed exactly once, and only non-secret metadata is persisted.

**Mint a token and capture the secret:**

```bash
export MINT=$(curl -s -X POST http://localhost:8080/api/agent/v1/service-tokens \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"claude-code-laptop"}')
echo "$MINT" | python3 -m json.tool

export ALX_TOKEN=$(echo "$MINT" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
export ALX_TOKEN_ID=$(echo "$MINT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "ALX_TOKEN=$ALX_TOKEN"
```

- [ ] Expected: HTTP body with `id`, `name`, `tokenPrefix` (starts `alx_`, 12 chars), `scope` =
  `"agent_read"`, `expiresAt` = `null`, and a `token` starting with `alx_`.
- [ ] Expected: **no** `tokenHash` field in the response.

**Confirm the secret does not appear in the listing (metadata only):**

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/agent/v1/service-tokens | python3 -m json.tool
```

- [ ] Expected: your token appears by `id` / `tokenPrefix`, with **no** `token` or `tokenHash` field.

**A non-admin cannot mint.** (Optional) The default mock user is always `Org_Admin`, so to see the
`403` path, call as an unauthenticated request:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/service-tokens \
  -H "Content-Type: application/json" -d '{"name":"nope"}'
```

- [ ] Expected: `401` (no credential). With a non-admin user credential it would be `403`.

---

## 2. Seed a resolvable skill (so tools return data)

`resolve_skill` only returns **approved, org-visible, published** skills, so seed at least one before
testing retrieval inside the client. The fastest path is section **0.3** of the
[Skill Resolver manual test](./skill-resolver-manual-test.md) (create a team + skills, then the SQL
block that sets `Company_Approved` / `organization` and rebuilds search vectors).

Alternatively, run the [skills import script](#appendix-bulk-import) in the appendix to bulk-load the
Anthropic skills library.

- [ ] Expected: at least one skill resolvable — verify quickly with the minted token:

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/skills/resolve \
  -H "Authorization: Bearer $ALX_TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"pdf","limit":5}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('results:', len(d['results']))"
```

> Adjust the `query` to match seeded content. `initialize` and `tools/list` (section 3) work even
> with zero skills — connection is provable regardless of seeding.

---

## 3. Transport sanity checks (raw JSON-RPC)

**What we're verifying:** the MCP endpoint speaks the Streamable HTTP transport correctly before any
client is involved. All calls use the **minted** `$ALX_TOKEN`.

**initialize:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $ALX_TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}' | python3 -m json.tool
```

- [ ] Expected: `result.protocolVersion` = `"2025-06-18"`, `result.serverInfo.name` =
  `"alexandria-skill-resolver"`.

**tools/list advertises exactly two tools:**

```bash
curl -s -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $ALX_TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | python3 -c "import sys,json; print([x['name'] for x in json.load(sys.stdin)['result']['tools']])"
```

- [ ] Expected: `['resolve_skill', 'fetch_skill']`.

**A notification (no `id`) is acknowledged with `202` and no body:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $ALX_TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

- [ ] Expected: `202`.

**A dual `Accept` header is accepted and returns `application/json`:**

```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $ALX_TOKEN" \
  -H "Accept: application/json, text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' | grep -i "^content-type"
```

- [ ] Expected: `Content-Type: application/json`.

**GET and DELETE are not allowed (no SSE stream, no session):**

```bash
curl -s -o /dev/null -w "GET=%{http_code}\n"    -H "Authorization: Bearer $ALX_TOKEN" http://localhost:8080/api/agent/v1/mcp
curl -s -o /dev/null -w "DELETE=%{http_code}\n" -X DELETE -H "Authorization: Bearer $ALX_TOKEN" http://localhost:8080/api/agent/v1/mcp
```

- [ ] Expected: `GET=405` and `DELETE=405`.

**Unauthenticated request is rejected:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}'
```

- [ ] Expected: `401`.

---

## 4. Connect Claude Code

**What we're verifying:** Claude Code connects to the endpoint and can call the tools.

**Add the server (CLI):**

```bash
claude mcp add --transport http alexandria \
  http://localhost:8080/api/agent/v1/mcp \
  --header "Authorization: Bearer $ALX_TOKEN"
```

Or add a `.mcp.json` at your project root (paste your real token):

```json
{
  "mcpServers": {
    "alexandria": {
      "type": "http",
      "url": "http://localhost:8080/api/agent/v1/mcp",
      "headers": { "Authorization": "Bearer alx_...your token..." }
    }
  }
}
```

> Use `http://localhost` (not `https`) for the local stack. In production use the HTTPS host from the
> [Connecting MCP Clients guide](../guide/mcp-clients-setup.md).

Restart Claude Code / reload MCP servers, then:

```
/mcp
```

- [ ] Expected: `alexandria` is listed as connected, exposing `resolve_skill` and `fetch_skill`.

**Exercise the tools from a prompt:**

> Use the alexandria MCP server to find a skill for the task I describe, then fetch its full
> instructions. Task: "create a PDF report from data."

- [ ] Expected: Claude calls `resolve_skill`, picks a candidate, then calls `fetch_skill` and shows
  the returned instructions. (Requires at least one seeded, resolvable skill — see section 2.)

---

## 5. (Reference) Copilot and Kiro config

The same endpoint and token work for other MCP clients; only the file and key names differ.

**GitHub Copilot (VS Code)** — `.vscode/mcp.json` (top-level `servers`):

```json
{
  "servers": {
    "alexandria": {
      "type": "http",
      "url": "http://localhost:8080/api/agent/v1/mcp",
      "headers": { "Authorization": "Bearer alx_..." }
    }
  }
}
```

- [ ] Expected: **MCP: List Servers** shows `alexandria` connected.

**Kiro** — `.kiro/settings/mcp.json` (`mcpServers`):

```json
{
  "mcpServers": {
    "alexandria": {
      "url": "http://localhost:8080/api/agent/v1/mcp",
      "headers": { "Authorization": "Bearer alx_..." },
      "disabled": false
    }
  }
}
```

- [ ] Expected: the MCP Server view shows `alexandria` connected with the two tools.

---

## 6. Revoke and confirm deauthentication

**What we're verifying:** revocation is immediate and idempotent.

**Revoke the minted token by id:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  "http://localhost:8080/api/agent/v1/service-tokens/$ALX_TOKEN_ID" \
  -H "Authorization: Bearer $TOKEN"
```

- [ ] Expected: `204`.

**The revoked secret no longer authenticates the MCP endpoint:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/agent/v1/mcp \
  -H "Authorization: Bearer $ALX_TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list"}'
```

- [ ] Expected: `401`. (In Claude Code, tool calls now fail to authenticate.)

**Revoking again is idempotent:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  "http://localhost:8080/api/agent/v1/service-tokens/$ALX_TOKEN_ID" \
  -H "Authorization: Bearer $TOKEN"
```

- [ ] Expected: `204`.

**Revoking an unknown id is a 404:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  "http://localhost:8080/api/agent/v1/service-tokens/00000000-0000-0000-0000-0000000000ff" \
  -H "Authorization: Bearer $TOKEN"
```

- [ ] Expected: `404`.

To keep testing, mint a fresh token (section 1) and re-add it to your client.

---

## Appendix: Bulk import {#appendix-bulk-import}

To populate the catalog with realistic content for the retrieval steps, use the Anthropic skills
importer:

```bash
export TOKEN   # Org_Admin token from 0.2
scripts/import-anthropic-skills.sh
```

See the script's `--help` for options (dry run, team name, target host). After import, the seeded
skills are `Draft` by default; promote a few to `Company_Approved` / `organization` (see the resolver
manual test, section 0.3) so `resolve_skill` returns them.
