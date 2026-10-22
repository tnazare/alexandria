# Alexandria CI/CD on TeamCity Cloud — 6-Session Build Plan

A session-by-session plan for standing up a full CI/CD pipeline for Alexandria on
**TeamCity Cloud**, structured to line up 1:1 with the JetBrains Cloud Diary
(Entry 1 → Entry 6 / Final). Each session is a self-contained chunk of work you can
record with Loom and then write up as a diary entry while it's fresh.

> Deadline for final entry: **26.10.2026**. Study window: ~2 weeks.

## What we're building (grounded in the actual repo)

| Area | Reality in this repo |
|------|----------------------|
| Repo | `git@github.com:tnazare/alexandria.git`, default branch `main`, **no CI yet** |
| Backend | Java 25, Spring Boot 4.0.7, Maven multi-module (`alexandria-api`, `alexandria-entities`, `alexandria-service`), `./mvnw` wrapper |
| Backend tests | **Testcontainers (Postgres)** + **jqwik** → the build agent **must have Docker available** |
| Backend commands | `./mvnw test` (unit), `./mvnw verify` (integration), `./mvnw package`, `flyway:migrate` |
| Backend image | Multi-stage `backend/Dockerfile` (eclipse-temurin:25) |
| Frontend | React 19 + Vite 8 + TypeScript; `npm run lint` (eslint), `npm run test` (vitest), `npm run build` (tsc + vite) |
| Frontend image | `frontend/Dockerfile` |
| Local stack | `docker-compose.yml` → Postgres (pgvector), mock-oauth2-server, backend, frontend |
| Docs | VitePress site under `docs/` |

### Target pipeline shape (build chain)

```
                 ┌─────────────────┐
   VCS trigger → │ 1. Lint & Compile│
                 └────────┬─────────┘
                          │ (parallel)
          ┌───────────────┴───────────────┐
          ▼                               ▼
 ┌──────────────────┐          ┌────────────────────┐
 │ 2. Backend Tests │          │ 3. Frontend Tests  │
 │ mvn verify       │          │ lint + vitest      │
 │ (Testcontainers) │          │                    │
 └────────┬─────────┘          └─────────┬──────────┘
          └───────────────┬──────────────┘
                          ▼
                 ┌──────────────────┐
                 │ 4. Package & Build│
                 │ Docker images     │
                 └────────┬─────────┘
                          ▼
                 ┌──────────────────┐
                 │ 5. Publish to GHCR│  (tag/main only)
                 └────────┬─────────┘
                          ▼
                 ┌──────────────────┐
                 │ 6. Deploy (opt.)  │  (manual/approval)
                 └──────────────────┘
```

Why this shape: lint/compile fails fast and cheaply; backend and frontend test
jobs run in parallel because they're independent; packaging only runs if tests
pass; publish/deploy are gated to protected refs. This is the production-ready
target — we grow into it across the 6 sessions rather than building it all at once.

---

## Session 1 — First contact + connect the repo (→ Diary Entry 1)

**Goal:** Get into TeamCity Cloud, connect the Alexandria GitHub repo, and get a
single green build that just compiles the backend. Prove the loop works end to end.

**Steps**
1. Sign up for TeamCity Cloud, create the Alexandria project.
2. Connect VCS root to `github.com/tnazare/alexandria` (GitHub App or token/SSH).
3. Create one build configuration "Backend — Compile".
4. Add a single Maven build step: `./mvnw -B -pl alexandria-api,alexandria-entities,alexandria-service -am compile`.
5. Pick a cloud build agent that has **Java 25** (or install via a step) — note whether the default agents satisfy Java 25.
6. Add a VCS trigger on `main`. Run it manually first, then push a trivial commit to confirm the trigger fires.

**Done when:** a commit to `main` automatically produces a green "compile" build.

**Watch for / record for the diary:**
- First-impression friction: project/VCS/agent mental model, where the "run" button is.
- Did Java 25 "just work" on the cloud agent, or did you need a custom image/toolchain?
- Where you looked for help (docs, UI hints, AI).

**Maps to Entry 1:** session duration, first steps, first impressions, repo connection, where you looked for help, emoji.

---

## Session 2 — Real backend build config: unit tests with Testcontainers (→ Diary Entry 2)

**Goal:** Turn the toy compile job into a real backend build that runs the test
suite — the first point where the pipeline tells you something true about the code.

**Steps**
1. Expand the backend config to `./mvnw -B test` (or `verify`).
2. **Docker-in-the-build problem:** backend tests spin up Postgres via Testcontainers.
   Confirm the agent exposes a Docker daemon. If cloud agents don't, this is a key
   decision point — options:
   - use a TeamCity cloud agent image/pool that provides Docker, or
   - run the step inside a Docker container runner that mounts the socket, or
   - fall back to a TeamCity-managed service container for Postgres + Spring profile override.
3. Enable **test reporting**: TeamCity auto-parses Surefire XML (`target/surefire-reports`).
   Verify jqwik + JUnit results show up in the Tests tab.
4. Cache the Maven repo (`~/.m2`) between builds to cut time.
5. Add build failure conditions on test failures.

**Done when:** a push runs the backend test suite on TeamCity and surfaces
pass/fail counts in the UI, with Testcontainers working.

**Watch for / record:** the Docker/Testcontainers setup is the likely "biggest
challenge" — document exactly how you resolved it. Note Maven caching and test
report rendering as "what worked well".

**Maps to Entry 2:** features/settings worked with, build config for the real
project, biggest challenges + resolution, what worked well.

---

## Session 3 — First real end-to-end run: add frontend + package (→ Diary Entry 3)

**Goal:** Run a real pipeline across the whole app on the actual codebase: backend
tests + frontend lint/test + packaging artifacts. This is the "run a real pipeline"
milestone the diary explicitly asks about.

**Steps**
1. Add a **Frontend** build config: Node 22 agent, `npm ci`, `npm run lint`, `npm run test`, `npm run build`.
2. Wire a **build chain** (snapshot dependencies): Lint/Compile → (Backend Tests ∥ Frontend Tests) → Package.
3. Package step: `./mvnw -B package -DskipTests` to produce the Spring Boot JAR, and `vite build` output; publish both as **build artifacts** in TeamCity.
4. Add VitePress docs build as an optional parallel job if time allows.
5. Review the chain visualization and timings.

**Done when:** one trigger fans out to parallel test jobs and converges on a
packaging job that publishes JAR + frontend bundle artifacts.

**Watch for / record:** how close this is to production (answer: tests + build +
artifacts done; images/publish/deploy still pending). The build-chain graph is a
likely "it clicked" moment. Capture what's still missing for Entry 3's blockers question.

**Maps to Entry 3:** features used, pipeline description + production-readiness,
what's still missing/blocking, the "click" moment, biggest challenges.

---

## Session 4 — Beyond basics: Docker images, parallelism, notifications, invite a teammate (→ Diary Entry 4)

**Goal:** Exercise the advanced TeamCity surface area the diary asks about.

**Steps**
1. Add image-build steps using `backend/Dockerfile` and `frontend/Dockerfile` (Docker runner or `docker build`).
2. Tag images with build number + VCS revision; wire **publish to GHCR** (`ghcr.io/tnazare/alexandria-*`) using a GitHub token stored as a TeamCity secret parameter — gated to `main`/tags only.
3. Tune **parallel builds** / agent concurrency; measure chain wall-clock improvement.
4. Configure **notifications** (Slack/email) and **commit status** back to GitHub PRs.
5. Try **Kotlin DSL / versioned settings**: enable "Settings in VCS" so the pipeline config lands in the repo as code (big story for an open-source project).
6. **Invite a teammate** to the TeamCity project (even a second account) and note the flow.

**Done when:** images build and push to GHCR on `main`, PRs get commit-status
checks, and pipeline config is stored as versioned settings.

**Watch for / record:** secrets handling, GHCR auth, DSL export quality, invite UX.

**JetBrains AI tooling to try (on-camera talking points):**
These are first-party tools that assist parts of this work. All current as of late
2026 — confirm your TeamCity Cloud version has the MCP/Pipelines features before
building a session around them (they landed in 2026.1–2026.2).

- **Kotlin DSL autocompletion in IntelliJ** — the key assist for writing the DSL live.
  The DSL is statically typed, so opening the generated `.teamcity/` project in IntelliJ
  gives full API autocompletion. This is what makes "author the DSL on camera" feasible
  without memorizing the API.
- **AI Assistant in the TeamCity UI** — debugging/analysis oriented (explain failed
  builds, read logs). As of 2026.2 supports BYOK (your own API key / local LLM). Great
  for the Testcontainers failure moments in Sessions 2–3; note it's UI-only and not a
  DSL generator.
- **TeamCity MCP server + free CLI** — lets an *external* agent (Claude Code / Cursor /
  Kiro) investigate failed builds, apply fixes, configure the pipeline, and retrigger
  from the terminal. 2026.2 added Pipelines support to the MCP. Neat symmetry to record:
  you're building Alexandria's own MCP layer while driving TeamCity through *its* MCP.
- **Junie (JetBrains coding agent)** — runs in IntelliJ; can draft Kotlin DSL blocks.
  JetBrains' own public `teamcity-ai-agent-testing-demo` repo is a real Kotlin-DSL
  pipeline example worth reading.

> Do not pre-install these as part of the plan — try them live and record first-run
> friction, which is exactly what the diary wants. Writing the DSL by hand (with IDE
> autocompletion) stays the primary path; the AI tools are an optional layer on top.

**Maps to Entry 4:** build chains/parallel/notifications/DSL/versioned settings,
inviting someone, challenges, what worked.

---

## Session 5 — Deploy stage, permissions, and compare to prior CI (→ Diary Entry 5)

**Goal:** Close the "CD" half and evaluate TeamCity as a team tool.

**Steps**
1. Add a **Deploy** build config (manual trigger or approval gate) that SSHes to the
   **Oracle Always Free VM**, pulls the GHCR images, and runs `docker compose pull &&
   docker compose up -d`. (See the **Deployment target & topology** section below for
   the concrete target this builds against.)
2. Add a **deployment/environments** view if using TeamCity's deployment dashboards.
3. Explore **user management / roles / permissions** at the project level.
4. Set up **clean-up rules** (artifact/history retention).
5. Reflect on comparison vs your current CI (GitHub Actions / Jenkins / etc.).

**Done when:** there's a gated deploy step and you've reviewed roles/permissions.

**Watch for / record:** anything you tried but couldn't do; the permissions model
experience; concrete better/worse/different vs your previous CI.

**Maps to Entry 5:** features used, comparison to prior CI, things unsupported/unclear,
user management experience, challenges.

---

## Session 6 — Harden, polish, and verdict (→ Diary Entry 6 + Final)

**Goal:** Make the pipeline something you'd actually keep, and form a judgment.

**Steps**
1. Add **PR/branch builds** (feature branches build without pushing images).
2. Add **build failure conditions** on quality signals (test count drop, duration spike).
3. Add status **badges** to the README; confirm commit statuses on PRs.
4. Finalize **versioned settings** committed to the repo so the whole pipeline is reproducible.
5. Do a full dry run from a fresh feature branch → PR → merge → image publish → deploy.
6. Write the Final entry: journey, turning points, best thing, biggest gap, decision, one suggestion.

**Done when:** a feature branch flows through the entire chain and `main` publishes
+ (optionally) deploys, with config living in the repo.

**Maps to Entry 6 + Final:** confidence score (1–5), full-journey narrative,
most valuable thing, biggest frustration, what you missed, continue decision,
one first-time-experience suggestion.

---

## Provider accounts & registration (do these before Session 1)

You'll need accounts across up to four providers. Do the first two before Session 1;
the Oracle/Cloudflare ones can wait until Session 5 (deploy) but registering early
avoids identity-verification delays. All figures below are current as of late 2026 and
worth re-checking at signup — free-tier terms change often.

### 1. GitHub (already have it)
The repo is `github.com/tnazare/alexandria`. For CI you'll additionally:
- Create a **fine-grained Personal Access Token** (or a GitHub App) so TeamCity can read
  the repo and report commit statuses. Scope it to just this repo.
- For GHCR image publishing (Session 4): create a token with `write:packages` (classic)
  or the fine-grained **Packages: write** permission. Store it as a TeamCity secret.

### 2. TeamCity Cloud (Session 1)
- Sign up at `teamcity.jetbrains.com` → start a cloud instance (`<yourname>.teamcity.com`).
- Signed in with a **JetBrains Account** (create one if needed).
- The **free trial** gives ~12,000 build credits (~20 build hours on a Linux *small*
  agent), unlimited parallel builds, 120 GB storage, and up to 3 self-hosted agents.
  Credits are the budget to watch — the Testcontainers backend build is the heaviest
  consumer, so don't leave builds looping.
- Authorize TeamCity's GitHub connection (OAuth/GitHub App) when prompted so the VCS
  root can attach without a manual token.
- Credit-card requirement for the trial has varied over time; have one ready but expect
  the trial itself not to charge. (TeamCity's standing "free plan" has come and gone —
  rely on the trial for this study.)

### 3. Oracle Cloud — Always Free VM (before Session 5)
- Sign up at `oracle.com/cloud/free`. One streamlined flow grants **Always Free**
  services (unlimited duration) plus a **$300 / 30-day trial** credit.
- Expect to provide: email (with a confirm-your-account verification link), a **mobile
  phone number**, a **home region** (choose carefully — Always Free resources live in
  your home region and it can't be changed later), and a **credit/debit card** for
  identity verification. The card is **not charged** unless you explicitly upgrade to
  Pay As You Go; Oracle may place a temporary authorization hold to validate it.
- After signup, in the OCI console: create a VM instance using the **Ampere A1 (Arm)**
  Always Free shape (currently 2 OCPU / 12 GB). Capacity for this shape is frequently
  "out of host capacity" — retry across availability domains, or use a community
  "catcher" script that retries the launch until one is allocated.
- Add your SSH public key during instance creation (TeamCity will deploy over SSH).
- Open the VM only to Cloudflare — ideally keep inbound ports closed and rely on the
  tunnel (next). At minimum restrict the security list / `iptables` to SSH from your IP.

### 4. Cloudflare — Tunnel + Access (before/with Session 5)
- Create a free account at `cloudflare.com` (email + password; verify email).
- **A domain is required for named tunnels and Access.** Options:
  - Add a domain you own to Cloudflare (change its nameservers to Cloudflare's — free
    plan is fine), **or**
  - For a short-lived trial, use a **quick tunnel** (`trycloudflare.com` hostname, no
    domain, no account needed) — fine for demos but the URL is ephemeral.
- Install `cloudflared` on the Oracle VM, authenticate it (`cloudflared tunnel login`),
  create a named tunnel, and route your hostname to the local frontend/backend ports.
- **Cloudflare Zero Trust (Access):** enable the Zero Trust dashboard (free up to 50
  users). Add an **email one-time-PIN** policy on the human paths only — see the
  security table below. No separate paid plan needed for this study.

### 5. OIDC identity provider (only for a non-mock deploy)
Locally Alexandria uses a mock OIDC server. A real deployment needs a real OIDC
provider. Free-tier developer options: **Auth0**, **Microsoft Entra ID (free tier)**,
**Google Workspace/Cloud OAuth**, or self-hosted **Keycloak** on the same VM. Register
an application/client there and set `OIDC_ISSUER_URI` / `OIDC_CLIENT_ID` accordingly.
You can defer this and keep the mock server until the deploy session if time is tight.

> Registration order that minimizes blocking: GitHub token → TeamCity trial (Sessions
> 1–4 need nothing else) → Oracle (allow a day for capacity/verification) → Cloudflare
> → OIDC. Capture each signup in the Loom recording; first-run friction is exactly what
> the diary asks about.

## Deployment target & topology

The free deploy target for this trial is an **Oracle Cloud Always Free ARM VM**
(2 OCPU / 12 GB as of mid-2026 — Oracle halved the old 4/24 allowance), fronted by a
**Cloudflare Tunnel**. This is the only free option where Alexandria's existing
`docker-compose.yml` deploys unchanged, Postgres (pgvector) included, with no inbound
ports opened on the VM.

### It is TWO containers, not three addresses

Alexandria is a **modular monolith**, and the frontend is a **single React SPA**. The
admin console (`src/admin/`) and the skill browser (`src/browser/`) are sections of the
*same* Vite build served by *one* nginx container — they are client-side routes, not
separate deployments.

| Container | What it serves | Exposed? |
|-----------|----------------|----------|
| `frontend` (nginx) | Whole SPA: admin at `/admin/*`, catalog at `/browse/*` | yes (web) |
| `backend` (Spring Boot) | REST API at `/api/*` **and** the MCP endpoint at `/api/agent/v1/mcp` | yes (API) |
| `postgres` (pgvector) | Database | no — internal only |
| OIDC provider | Login (mock locally; real IdP in a real deploy) | per IdP |

### Single-hostname path routing (recommended)

Expose everything under one hostname and let the Cloudflare Tunnel route by path. No
CORS to configure, one ingress ruleset:

```
alexandria.example.com
├─ /                   → frontend container   (SPA; /admin and /browse are client routes)
├─ /api/*              → backend container
└─ /api/agent/v1/mcp   → backend container    (same backend, specific path)
```

(Two-hostname split — `app.` + `api.` — also works, but then set
`VITE_API_BASE_URL=https://api.example.com` and open backend CORS for the app origin.)

### Minimal security layer, scoped by path

Cloudflare Tunnel already removes the attack surface (outbound-only, no open VM ports,
edge TLS + DDoS). For the "minimal security" gate, apply **Cloudflare Access**
(Zero-Trust, free up to 50 users, email one-time-PIN) **only to the human paths**:

| Path | Cloudflare policy | Why |
|------|-------------------|-----|
| `/`, `/api/*` (browser-driven) | Cloudflare Access (email PIN) | interactive human login |
| `/api/agent/v1/mcp`, `/api/agent/v1/service-tokens` | **No interactive Access** (bare tunnel, or Service-Auth header policy) | agents are non-interactive; a browser login page breaks them |

**Why the exemption matters:** the MCP endpoint runs inside Alexandria's own Spring
Security filter chain and already requires a bearer token (OIDC user token or an `alx_`
service token), with per-org ACL enforced per identity. Wrapping it in interactive
Access would bounce agent requests to a login page and fail them. Alexandria's
service-token auth is the real gatekeeper for that path.

### How a user/agent connects to MCP once deployed

1. An `Org_Admin` mints a service token: `POST /api/agent/v1/service-tokens` → copy the
   one-time `alx_...` secret.
2. The user points their agent (Claude Code / Copilot / Kiro) at
   `https://alexandria.example.com/api/agent/v1/mcp` with header
   `Authorization: Bearer alx_...`. The endpoint is stateless Streamable-HTTP JSON-RPC;
   it advertises exactly `resolve_skill` and `fetch_skill`.
3. See `docs/guide/mcp-clients-setup.md` for the per-client config blocks.

This deploy target is what Session 5 builds against, and standing up the Oracle VM +
Cloudflare Tunnel is itself a strong diary story (secrets, SSH deploy, zero open ports).

## Loom recording tips (per session)

- Start each recording by stating the session goal and the diary entry it feeds.
- Narrate decisions out loud ("the agent has no Docker, so I'm switching to…") —
  this is gold for UX researchers and makes the diary write-up trivial.
- End each recording on the result (green/red build) and a one-line emoji verdict.
- Keep a scratch note of session **duration** and the **help sources** you used —
  both are explicit diary fields.

## Risk register (likely friction points to document, not avoid)

1. **Java 25 agents** — may need a custom agent image or toolchain; capture it.
2. **Docker for Testcontainers** — biggest technical hurdle; the resolution is a
   great diary story.
3. **GHCR auth / secrets** — token scope and secret-parameter handling.
4. **Versioned settings / Kotlin DSL** — export fidelity vs hand editing.
5. **Deploy target** — Oracle Always Free VM running the compose stack (see topology
   section). Two gotchas: Oracle's free ARM shape is often "out of host capacity" at
   launch (people use a catcher script to grab one), and the free allowance was halved
   to 2 OCPU / 12 GB in mid-2026. Fly.io is no longer a free option (pay-as-you-go now),
   so avoid it if "free" is a hard requirement.
6. **Cloudflare Access vs MCP** — do NOT put interactive Access on `/api/agent/v1/mcp`;
   it breaks non-interactive agent clients. Scope Access to human paths only.

<!-- CI dry run: Session 6 branch build test -->
