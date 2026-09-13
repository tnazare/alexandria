# Connecting MCP Clients

Alexandria exposes its Skill Resolver to AI coding agents over the **Model Context Protocol (MCP)**
using the Streamable HTTP transport. This guide shows how to mint a credential and point
**Claude Code**, **GitHub Copilot (VS Code)**, and **Kiro** at your Alexandria deployment.

The MCP endpoint is:

```
https://<your-alexandria-host>/api/agent/v1/mcp
```

It advertises exactly two tools — `resolve_skill` (find governed skill candidates for a task) and
`fetch_skill` (retrieve the full instructions for a chosen skill version). Every request is
authenticated and scoped to the caller's organization; agents only ever see skills their credential
is allowed to see.

> The endpoint is a single POST URL. It is stateless: there is no SSE stream and no session id to
> manage. `GET` and `DELETE` on the endpoint return `405`, which is expected.

## 1. Mint a service token

Agents authenticate with a **service token** — a scoped, revocable, non-interactive credential. Any
organization administrator (`Org_Admin`) can mint one via the admin API.

Using an `Org_Admin` bearer token (an OIDC user token), create a service token:

```bash
curl -s -X POST https://<your-alexandria-host>/api/agent/v1/service-tokens \
  -H "Authorization: Bearer $ORG_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"claude-code-laptop"}'
```

The response contains the plaintext secret — **this is the only time it is ever shown**:

```json
{
  "id": "3f7c1e2a-...",
  "name": "claude-code-laptop",
  "tokenPrefix": "alx_QK8sD1zR",
  "scope": "agent_read",
  "expiresAt": null,
  "token": "alx_QK8sD1zRoP...<redacted>"
}
```

Copy the `token` value somewhere safe. Alexandria stores only a hash of it, so it cannot be
retrieved later — if you lose it, revoke it and mint a new one.

To set an expiry, include an ISO-8601 `expiresAt`:

```bash
curl -s -X POST https://<your-alexandria-host>/api/agent/v1/service-tokens \
  -H "Authorization: Bearer $ORG_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"ci-runner","expiresAt":"2027-01-01T00:00:00Z"}'
```

The rest of this guide refers to the minted secret as `alx_...`. In every client config, replace it
with your real token.

## 2. Claude Code

Claude Code reads MCP servers from a `.mcp.json` file (project root, or your user config). Add
Alexandria as an `http` server:

```json
{
  "mcpServers": {
    "alexandria": {
      "type": "http",
      "url": "https://<your-alexandria-host>/api/agent/v1/mcp",
      "headers": {
        "Authorization": "Bearer alx_..."
      }
    }
  }
}
```

Alternatively, add it from the terminal:

```bash
claude mcp add --transport http alexandria \
  https://<your-alexandria-host>/api/agent/v1/mcp \
  --header "Authorization: Bearer alx_..."
```

Restart Claude Code (or reload the MCP servers). You should see `resolve_skill` and `fetch_skill`
listed among the available tools.

## 3. GitHub Copilot (VS Code)

VS Code's Copilot agent mode reads MCP servers from `.vscode/mcp.json` in your workspace. Note that
VS Code uses a top-level `servers` key (not `mcpServers`):

```json
{
  "servers": {
    "alexandria": {
      "type": "http",
      "url": "https://<your-alexandria-host>/api/agent/v1/mcp",
      "headers": {
        "Authorization": "Bearer alx_..."
      }
    }
  }
}
```

To avoid committing the secret, VS Code supports input prompts. You can substitute the token with an
input variable instead of inlining it:

```json
{
  "inputs": [
    { "id": "alexandria-token", "type": "promptString", "description": "Alexandria service token", "password": true }
  ],
  "servers": {
    "alexandria": {
      "type": "http",
      "url": "https://<your-alexandria-host>/api/agent/v1/mcp",
      "headers": {
        "Authorization": "Bearer ${input:alexandria-token}"
      }
    }
  }
}
```

Open the Command Palette and run **MCP: List Servers** to confirm Alexandria is connected.

## 4. Kiro

Kiro reads MCP servers from `.kiro/settings/mcp.json` (workspace) or `~/.kiro/settings/mcp.json`
(user level):

```json
{
  "mcpServers": {
    "alexandria": {
      "url": "https://<your-alexandria-host>/api/agent/v1/mcp",
      "headers": {
        "Authorization": "Bearer alx_..."
      },
      "disabled": false
    }
  }
}
```

Kiro reconnects automatically when the config changes, or you can reconnect from the MCP Server view
in the feature panel.

## 5. Verify the connection

Once connected, ask the agent to find a skill — for example, *"use Alexandria to find a skill for
writing Kafka integration tests."* The agent should call `resolve_skill`, then `fetch_skill` on the
candidate it picks.

To sanity-check the endpoint directly, list the advertised tools with a raw JSON-RPC call:

```bash
curl -s -X POST https://<your-alexandria-host>/api/agent/v1/mcp \
  -H "Authorization: Bearer alx_..." \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

You should get a result listing exactly `resolve_skill` and `fetch_skill`. A `401` means the token
is missing, wrong, revoked, or expired.

## 6. Managing tokens

List your organization's tokens (metadata only — never the secret):

```bash
curl -s https://<your-alexandria-host>/api/agent/v1/service-tokens \
  -H "Authorization: Bearer $ORG_ADMIN_TOKEN"
```

Revoke a token by id (idempotent — revoking again is harmless). A revoked token stops
authenticating immediately:

```bash
curl -s -X DELETE https://<your-alexandria-host>/api/agent/v1/service-tokens/<tokenId> \
  -H "Authorization: Bearer $ORG_ADMIN_TOKEN"
```

Token minting, listing, and revocation all require the `Org_Admin` role, and every mint and revoke
is recorded in the audit log.

## 7. Importing the Anthropic skills library

To populate a fresh deployment with realistic content, the repository ships an importer that loads
every skill from Anthropic's public [`anthropics/skills`](https://github.com/anthropics/skills)
library into your catalog. Each `skills/<slug>/SKILL.md` becomes one Alexandria skill (name derived
from the slug, description from the SKILL.md frontmatter, full SKILL.md as the canonical
instructions), created under an owner team named "Anthropic Skills".

The importer talks to the same REST API as everything else, so it needs an `Org_Admin` bearer token.
Set `TOKEN`, then run the wrapper script:

```bash
export TOKEN=$ORG_ADMIN_TOKEN

# Preview without writing anything:
scripts/import-anthropic-skills.sh --dry-run

# Import all skills into a local stack:
scripts/import-anthropic-skills.sh

# Import into a remote host, or a subset of skills:
scripts/import-anthropic-skills.sh --host https://<your-alexandria-host> --only pdf,xlsx,mcp-builder
```

Flags (all optional; run with `--help` for the full list):

- `--host` — Alexandria base URL (default `http://localhost:8080`, or set `ALEXANDRIA_HOST`).
- `--team` — owner team name (default `Anthropic Skills`; created if absent).
- `--only` — comma-separated slugs to import (default: all).
- `--dry-run` — show what would be imported without calling the API.

Requirements: `python3` (the importer uses only the standard library plus PyYAML when available;
it falls back to a built-in frontmatter parser otherwise).

> Imported skills start in **Draft** / **private**. Promote the ones you want the resolver to return
> to `Company_Approved` / `organization` before agents can retrieve them — see the
> [Skill Resolver manual test](../manual-testing/skill-resolver-manual-test.md) (section 0.3) for the
> `UPDATE` statements, scoped to the "Anthropic Skills" team.
