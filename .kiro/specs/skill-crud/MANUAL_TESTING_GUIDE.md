# Skill CRUD — Manual Testing Guide

This guide walks through manually verifying everything built in the **skill-crud**
milestone: the Skill REST API, tool-specific variants, the controlled taxonomy,
lifecycle/publishing rules, visibility, versioning, and the frontend authoring UI.

It assumes you are running the full local stack with the bundled mock OIDC provider.

---

## 1. Prerequisites

- Docker + Docker Compose
- (Optional, for the API-only path) `curl` and `jq`
- Ports free: `9000` (mock OIDC), `5432` (Postgres), `8080` (backend), `5173` (frontend)

---

## 2. Start the stack

From the repository root:

```bash
docker compose up --build
```

This starts four services:

| Service        | URL                          | Purpose                              |
| -------------- | ---------------------------- | ------------------------------------ |
| `heptastadion` | http://localhost:9000        | Mock OIDC identity provider          |
| `postgres`     | localhost:5432               | Database (Flyway migrations run on boot) |
| `backend`      | http://localhost:8080        | REST API (Spring Boot)               |
| `frontend`     | http://localhost:5173        | Next/React UI                        |

Wait until the backend health check is green:

```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}
```

### About the dev login

The mock OIDC server auto-issues a token for a single user:

- **Email:** `dev@alexandria.local`
- **Role:** `Org_Admin`
- **Organization:** `Default Organization`

Because the dev user is an **Org_Admin**, they can create teams, manage taxonomy,
and edit/publish any skill. There is **no seeded team or taxonomy** — you create
those in the first steps below.

> Note: the mock provider always returns `Org_Admin` regardless of who logs in.
> Testing role-restricted negative paths (e.g. a non-member getting 403) requires
> either the API approach in §9 with a crafted second user, or editing
> `docker-compose.yml` (`heptastadion` `JSON_CONFIG`) to add a second identity.
> This is called out where relevant.

---

## 3. Log in

1. Open http://localhost:5173
2. You are redirected to the mock OIDC login. Submit the login form (any values;
   the mock issues the `dev@alexandria.local` / `Org_Admin` token).
3. You land on the app. As an admin you are redirected to `/admin`.

**Expected:** top-right shows `dev@alexandria.local`. Admin sidebar shows
Users, Teams, Taxonomy, Audit Logs.

---

## 4. Set up prerequisites (team + taxonomy)

Skills must belong to a team, and publishing requires a `task_type` tag, so set
these up first.

### 4.1 Create a team

1. Go to **Admin → Teams** (`/admin/teams`).
2. Click **Create Team**, name it e.g. `Platform Engineering`, save.

**Expected:** 201, the team appears in the list with 1 member (you, the creator,
as owner). ✅ _Requirement 3.1_

### 4.2 Add taxonomy entries

1. Go to **Admin → Taxonomy** (`/admin/taxonomy`).
2. On the **task_type** tab, add a value, e.g. `refactoring`. Add a description
   if you like.
3. Switch to the **language** tab and add `Java`. Add `framework` → `Spring` and
   `tool` → `maven` if you want more to filter on later.

**Expected for each add:** the row appears with status **Active**. ✅ _Requirements 7.3, 20.1_

**Negative — duplicate value:** try adding `refactoring` again on task_type.
**Expected:** rejected with a conflict message (case-insensitive uniqueness). ✅ _Requirement 7.10_

---

## 5. Create a skill (Draft)

1. Go to **Skills** (`/catalog/skills`), click **New skill**
   (or navigate to `/catalog/skills/new`).
2. Fill in:
   - **Name:** `Extract Method Helper`
   - **Description:** `Guides safe method extraction in existing code`
   - **Owning team:** `Platform Engineering`
   - **Canonical instructions:** any Markdown, e.g. `## Steps\n1. Identify the block\n2. Extract`
3. Try the **Edit/Preview** toggle on the instructions editor — Preview shows the
   raw Markdown source.
4. Click **Create skill**.

**Expected:** redirected to the skill detail page. Status badge = **Draft** (gray),
version = 1, visibility = private. ✅ _Requirements 5.1, 5.2, 6.4_

### 5.1 Client-side validation

Go back to **New skill** and try to submit with:
- Name of 2 characters → "Name must be 3–200 characters"
- Empty description → "Description must be 1–2000 characters"
- No team selected → "Select an owning team"
- Empty instructions → "Instructions must be 1–100000 characters"

**Expected:** inline errors, no navigation, nothing created. ✅ _Requirement 5.3_

---

## 6. Edit the draft (in-place, no new version)

1. On the skill detail page, click **Edit**.
2. Fill in the sidebar metadata:
   - **Visibility:** leave `private` for now (note the description under the dropdown)
   - **Risk level:** `low`
   - **Supported tools:** check `cursor` and `aws_kiro`
   - **Tags:** in the Tag Picker, select `refactoring` (task_type) and `Java` (language)
3. Fill the content fields:
   - **Applicability:** `Java projects using method-level refactors`
   - **Examples:** `Before: long method. After: extracted helper.`
4. Watch the **Publish readiness** checklist update live as you fill fields —
   each satisfied item flips from ✗ (red) to ✓ (green).
5. Click **Save**.

**Expected:** saved, still **version 1** (draft edits are in-place, no new version). ✅ _Requirements 6.4, 7.1_

**Tag Picker check:** only **active** taxonomy entries appear as options. (You can
verify this later after deactivating one in §11.)

---

## 7. Publish — validation then success

### 7.1 Publish with something missing

1. Edit the skill again and clear **Examples** (or create a second fresh draft
   without examples), Save.
2. On the detail page, click **Publish**.

**Expected:** a 422 error toast listing the missing field(s), e.g.
`Cannot publish. Missing: examples`. The skill stays **Draft**. ✅ _Requirements 7.1, 7.2_

### 7.2 Publish when complete

1. Re-add **Examples**, Save. Confirm the Publish checklist is all green.
2. Click **Publish**.

**Expected:** status badge changes to **Published** (green). The Delete button
disappears (only drafts can be deleted). ✅ _Requirements 7.3, 7.4_

---

## 8. Versioning (editing a published skill)

1. On the published skill, click **Edit**.
2. Because the skill is no longer a draft, the **Changelog** field is now
   **required**. Try to Save with an empty changelog.
   **Expected:** "A changelog is required when editing a published skill".
3. Change the canonical instructions, enter a changelog like
   `Clarified extraction steps`, and Save.

**Expected:** a **new version (v2)** is created and the skill reverts to **Draft**. ✅ _Requirement 6.1_

### 8.1 Version history

1. On the detail page click **Version history** (`/catalog/skills/:id/versions`).
2. You should see **v2** and **v1** in descending order, each with its changelog
   and created/published timestamp.
3. Click **v1** → opens the version detail page showing that version's content
   and any variants. Use the **← Back to version history** link. ✅ _Requirements 2.6, 2.7_

---

## 9. Variants (tool-specific instructions)

On the skill detail page:

1. Click **Manage variants**.
2. In the **Add a variant** panel, choose a tool (e.g. `cursor`), enter
   Markdown instructions, click **Add variant**.
   **Expected:** the variant is created; a new **cursor** tab appears above the
   instructions view. ✅ _Requirement 12.1_
3. Switch tabs between **Canonical** and **cursor** — the instructions view
   changes accordingly; tools without a variant fall back to canonical. ✅ _Requirement 12.4_
4. Edit the cursor variant's instructions and click **Save changes**. ✅
5. Try adding a second variant — note that the tool dropdown no longer offers
   `cursor` (unique per tool per version). ✅ _Requirement 12.3_
6. Delete the cursor variant; confirm the tab disappears.

**Negative — duplicate variant (API):** attempting to create a second `cursor`
variant on the same version returns **409** (see §12).

---

## 10. Visibility

On the detail page, the **Visibility** dropdown (top control bar) lets you change
scope. Change it to `organization`, then `team`, then `private`, observing the
description text update.

**Expected:** each change persists (reload to confirm). ✅ _Requirements 8.1–8.3_

> To fully verify visibility **enforcement** (a non-member being denied), you need
> a second, non-admin user — see the note in §2. With the single admin dev user,
> the skill is always visible to you. The enforcement logic is covered by the
> automated property tests (`SkillVisibilityEnforcementPropertyTest`).

---

## 11. Taxonomy deactivation

1. Go to **Admin → Taxonomy**, language tab.
2. Add a throwaway value `COBOL`, then click **Deactivate** on it and confirm.
   **Expected:** status flips to **Inactive**. ✅ _Requirement 7.5_
3. Go edit a skill and open the Tag Picker.
   **Expected:** `COBOL` is **not** offered as a selectable option (inactive
   entries are filtered out), while previously-assigned tags remain intact. ✅ _Requirement 7.12_

---

## 12. Skill deletion

1. Create a fresh **Draft** skill (§5).
2. On its detail page, click **Delete**, confirm.
   **Expected:** 204, redirected to the catalog; the skill is gone. ✅ _Requirement — Draft delete_
3. Publish another skill, then confirm the **Delete** button is not shown for it.
   (Via API, deleting a published skill returns **409**.) ✅

---

## 13. Search & filters (skill list)

On the **Skills** list page (`/catalog/skills`):

1. Type in the search box → results filter by name/description (debounced).
   The URL updates with `?search=...` (shareable/bookmarkable).
2. Use the **Status**, **Visibility**, and **Team** filters — the list narrows and
   the URL reflects each filter.
3. Reload the page with filters in the URL → filters are restored. ✅ _Requirements 2.3, 9.1_

---

## 14. Audit log

Go to **Admin → Audit Logs** (`/admin/audit`). You should see events recorded for
the actions above: `skill.created`, `skill.updated`, `skill_version.created`,
`skill.published`, `skill.visibility_changed`, `skill.deleted`, `taxonomy.created`,
`taxonomy.deactivated`, `skill_variant.created/updated/deleted`, filterable by
action/actor/target/time. ✅ _Requirement 18.1_

---

## 15. (Optional) Direct API testing with curl

Useful for the negative paths that are hard to reach through the admin UI.

### 15.1 Get a token from the mock OIDC provider

```bash
TOKEN=$(curl -s -X POST http://localhost:9000/alexandria/token \
  -d "grant_type=client_credentials" \
  -d "client_id=alexandria-client" \
  -d "client_secret=secret" \
  -d "scope=openid" | jq -r .access_token)
echo "$TOKEN"
```

> The mock server issues an `Org_Admin` token for `dev@alexandria.local`.
> The `client_secret` value is not validated by the mock server, but the request
> must include one — omitting it returns `401 invalid_client`.

### 15.2 Confirm identity

```bash
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN" | jq
```

### 15.3 Create a team, taxonomy entry, and a skill

```bash
# Create a team
TEAM_ID=$(curl -s -X POST http://localhost:8080/api/teams \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"API Team","description":"created via curl"}' | jq -r .id)

# Create a task_type taxonomy entry
TAG_ID=$(curl -s -X POST http://localhost:8080/api/taxonomy/task_type \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"api-refactoring"}' | jq -r .id)

# Create a skill (Draft)
SKILL_ID=$(curl -s -X POST http://localhost:8080/api/skills \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"API Skill\",\"description\":\"via curl\",\"ownerTeamId\":\"$TEAM_ID\",\"canonicalInstructions\":\"do it\"}" | jq -r .id)
echo "skill=$SKILL_ID"
```

### 15.4 Verify the negative paths

```bash
# Publish incomplete -> 422 with missingFields
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost:8080/api/skills/$SKILL_ID/publish \
  -H "Authorization: Bearer $TOKEN"          # expect 422

# Invalid create payload (name too short) -> 400
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/skills \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"ab\",\"description\":\"x\",\"ownerTeamId\":\"$TEAM_ID\",\"canonicalInstructions\":\"x\"}"   # expect 400

# Unknown skill -> 404
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/api/skills/00000000-0000-0000-0000-000000000000 \
  -H "Authorization: Bearer $TOKEN"          # expect 404

# Duplicate taxonomy value -> 409
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost:8080/api/taxonomy/task_type \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"api-refactoring"}'           # expect 409

# Delete a Draft skill -> 204
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  http://localhost:8080/api/skills/$SKILL_ID \
  -H "Authorization: Bearer $TOKEN"          # expect 204
```

---

## 16. Tear down

```bash
docker compose down          # stop services, keep the database volume
docker compose down -v       # also wipe the Postgres volume (fresh start)
```

---

## Coverage checklist

| Area                              | Where verified            |
| --------------------------------- | ------------------------- |
| Team creation                     | §4.1                      |
| Taxonomy CRUD + uniqueness        | §4.2, §11                 |
| Skill creation + validation       | §5, §5.1                  |
| Draft in-place edit               | §6                        |
| Publish validation (422)          | §7.1                      |
| Publish success                   | §7.2                      |
| Versioning on published edit      | §8                        |
| Version history + version detail  | §8.1                      |
| Variants CRUD + fallback + unique | §9                        |
| Visibility change                 | §10                       |
| Taxonomy deactivation filtering   | §11                       |
| Draft delete / published 409      | §12                       |
| Search + filters + URL sync       | §13                       |
| Audit logging                     | §14                       |
| API negative paths                | §15                       |

## Known limitations of this local setup

- The mock OIDC provider only issues an **Org_Admin** identity, so UI-driven tests
  of role/membership **denial** (403) and cross-user **visibility** hiding require
  editing `docker-compose.yml` to add a second identity. These paths are covered by
  the backend property/integration tests (`SkillEditAuthorityPropertyTest`,
  `SkillVisibilityEnforcementPropertyTest`, `RbacEndpointAccessIntegrationTest`).
- Automated coverage: backend `./mvnw verify` (221 tests) and frontend
  `npm run test` (36 tests) both pass.
