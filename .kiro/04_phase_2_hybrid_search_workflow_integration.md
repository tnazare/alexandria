# Alexandria Phase 2: Hybrid Search and Developer Workflow Integration

## Phase 2 objective

Phase 2 turns the governed catalog into a practical discovery layer embedded in developer workflows.

Phase 1 answered:

> Can teams publish, govern, and manually reuse AI skills in a self-hosted environment?

Phase 2 answers:

> Can developers find the right skill from their actual coding context without leaving their workflow?

The product should move from passive catalog to active engineering assistant layer — still fully self-hosted within the organization's infrastructure.

## Phase 2 outcomes

At the end of Phase 2, a developer should be able to:

1. open an IDE extension
2. search for a skill using natural language
3. automatically include safe repository context in the query
4. receive ranked recommendations
5. understand why each skill was recommended
6. copy or export the skill into their AI coding tool
7. send feedback directly from the IDE or CLI

Example:

```text
Developer searches from VS Code:
"review this API change for auth problems"

Alexandria returns:
1. Tenant-scoped API authorization review
2. PII-safe export endpoint checklist
3. NestJS controller security review

Each result explains:
- matched TypeScript and NestJS
- approved by Security Guild
- compatible with GitHub Copilot
- relevant to selected controller file
```

All search, embedding, and ranking happens within the organization's deployed Alexandria instance.

## In scope

### Search

- hybrid keyword and semantic search
- vector embeddings (pgvector)
- metadata filters
- deterministic contextual ranking
- query understanding
- basic query expansion
- search explanations
- search quality analytics

### Developer integrations

- VS Code extension (connects to organization's Alexandria instance)
- CLI
- copy/export from IDE
- local file export with confirmation

### Context-aware discovery

- language detection
- framework detection
- dependency detection
- active file path
- preferred AI tool
- team identity
- safe repository metadata
- optional selected text with explicit permission

### Ranking

- semantic relevance
- keyword relevance
- applicability
- approval state
- trust
- freshness
- tool compatibility
- team affinity
- risk penalties

### Feedback

- worked well
- not relevant
- wrong framework
- outdated
- unsafe
- unclear
- copied/exported
- added to local instructions

### Admin

- IDE context policy
- allowed integrations
- allowed export targets
- default search filters
- tool settings
- search analytics
- embedding provider configuration

## Out of scope

- automatic code modification
- autonomous skill execution
- multi-skill composition
- full LLM adaptation
- full repository indexing
- deep PR/CI telemetry
- ML-trained ranking
- drift detection
- full evaluation harness

These belong in later phases.

## Updated architecture

```text
Web App / VS Code / CLI
        ↓
Context Collection SDK
        ↓
Alexandria API (self-hosted)
        ↓
Candidate Retrieval
   ┌───────────────┬───────────────┬───────────────┐
   ↓               ↓               ↓
Keyword Search   Vector Search   Metadata Filters
   ↓               ↓               ↓
        Candidate Merge and Deduplication
        ↓
        Ranking Service
        ↓
        Explanation Builder
        ↓
        Export Service
```

## Backend modules added

```text
SearchModule (enhanced)
EmbeddingModule
ContextModule
IntegrationModule
RecommendationModule
ExplanationModule
CliAuthModule
```

The backend remains a modular monolith — same deployment, same container.

## Hybrid search implementation

### Keyword search

Good for exact terms:

- framework names
- tools
- error codes
- APIs
- internal service names
- task names

Implementation:

- PostgreSQL full-text search (already in Phase 1)

### Vector search

Good for semantic intent.

Example:

```text
"make sure this endpoint does not leak tenant data"
```

Should match:

```text
"Tenant-scoped authorization review checklist"
```

Implementation:

- pgvector extension in the existing PostgreSQL database
- no additional infrastructure required

### Metadata filtering

Metadata should remain authoritative for facts.

Filter by:

- ACL
- lifecycle state
- visibility
- language
- framework
- task type
- tool
- risk level
- approval status
- owner team
- freshness

## Embedding design

Embed multiple chunks per skill version.

Chunks:

1. summary
2. canonical instructions
3. when to use
4. when not to use
5. examples
6. limitations
7. tool-specific variants
8. combined search document

Schema:

```sql
CREATE TABLE skill_embedding_chunks (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  skill_id UUID NOT NULL REFERENCES skills(id),
  skill_version_id UUID NOT NULL REFERENCES skill_versions(id),
  chunk_type TEXT NOT NULL,
  chunk_text TEXT NOT NULL,
  chunk_hash TEXT NOT NULL,
  embedding VECTOR(1536) NOT NULL,
  embedding_model TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (skill_version_id, chunk_type, chunk_hash)
);
```

Vector index example:

```sql
CREATE INDEX idx_skill_embedding_vector
ON skill_embedding_chunks
USING ivfflat (embedding vector_cosine_ops);
```

Use HNSW if available and appropriate for the chosen pgvector version and scale.

## Embedding provider configuration

The deployer configures the embedding provider through environment variables:

```text
EMBEDDING_PROVIDER=bedrock|openai|azure_openai|ollama|custom
EMBEDDING_MODEL=amazon.titan-embed-text-v2
EMBEDDING_API_KEY=... (if required)
EMBEDDING_ENDPOINT=... (for custom or Ollama)
EMBEDDING_DIMENSIONS=1536
```

Supported providers:

- AWS Bedrock (recommended for AWS-native deployments)
- OpenAI
- Azure OpenAI
- Ollama (fully local, no external API calls)
- Custom endpoint (organization-provided)

Organizations that want zero external API calls can use Ollama or a self-hosted model.

## Embedding update flow

When a skill version changes:

```text
Skill version created
  ↓
Search index update job queued (SQS)
  ↓
Content chunks generated
  ↓
Content hashes compared
  ↓
Embeddings generated only for changed chunks
  ↓
Embeddings stored
  ↓
Skill version marked searchable
```

## Hybrid retrieval flow

```text
Receive query and context
  ↓
Authorize user
  ↓
Build search constraints
  ↓
Run keyword search
  ↓
Run vector search
  ↓
Run metadata match
  ↓
Apply ACL and lifecycle filters
  ↓
Merge candidates
  ↓
Deduplicate
  ↓
Rank
  ↓
Generate explanations
  ↓
Return results
```

## Search API

### Endpoint

```http
POST /api/search/skills
```

### Request

```json
{
  "query": "generate integration tests for kafka consumer",
  "source": "vscode",
  "context": {
    "repository": {
      "name": "event-processing-service",
      "languages": ["java"],
      "frameworks": ["spring_boot", "kafka"],
      "build_tools": ["maven"]
    },
    "workspace": {
      "active_file_path": "src/main/java/com/acme/events/Consumer.java",
      "selected_text_summary": null
    },
    "tool": {
      "preferred": "github_copilot"
    },
    "team_id": "platform-events"
  },
  "filters": {
    "approval_states": ["team_approved", "company_approved"],
    "risk_levels": ["low", "medium"],
    "tools": ["github_copilot", "generic"]
  },
  "limit": 10
}
```

### Response

```json
{
  "results": [
    {
      "skill_id": "uuid",
      "skill_version_id": "uuid",
      "name": "Kafka Consumer Integration Test Workflow",
      "description": "Generate integration tests for Kafka consumers using Spring Boot and Testcontainers.",
      "score": 87.4,
      "badges": [
        "Company approved",
        "Java",
        "Spring Boot",
        "Kafka",
        "GitHub Copilot compatible"
      ],
      "explanation": {
        "matched": [
          "Query matched integration test examples",
          "Repository language matches Java",
          "Framework matches Spring Boot and Kafka",
          "Preferred tool GitHub Copilot is supported",
          "Approved by Platform Engineering"
        ],
        "warnings": [
          "Requires Testcontainers support in repository"
        ]
      }
    }
  ]
}
```

## Ranking formula

Use deterministic ranking in Phase 2.

```text
final_score =
  0.30 * semantic_relevance
+ 0.25 * keyword_relevance
+ 0.15 * applicability
+ 0.10 * trust
+ 0.08 * freshness
+ 0.07 * tool_compatibility
+ 0.05 * team_affinity
- risk_penalty
- stale_penalty
- mismatch_penalty
```

### Feature definitions

#### Semantic relevance

Vector similarity between the query/context and skill embeddings.

#### Keyword relevance

Full-text match or BM25 score.

#### Applicability

Matches between current context and skill metadata:

- language
- framework
- task type
- repository pattern
- required files
- architecture style

#### Trust

Based on:

- approval state
- owner reputation
- reviewer type
- usage count
- feedback

#### Freshness

Based on:

- last updated
- last reviewed
- approval expiration
- deprecated status

#### Tool compatibility

Boost if the skill has a native variant for the preferred tool.

#### Team affinity

Boost if:

- same team owns it
- team collection includes it
- sibling team uses it
- similar repositories use it

#### Risk penalty

Downrank high-risk skills unless explicitly requested.

## VS Code extension

### Features

Commands:

```text
Alexandria: Search Skills
Alexandria: Find Skills for Current File
Alexandria: Find Skills for Selection
Alexandria: Export Skill to Clipboard
Alexandria: Add Skill to AGENTS.md
Alexandria: Add Skill to Copilot Instructions
Alexandria: Send Feedback
Alexandria: Configure Instance URL
```

### Side panel

The extension should include:

- instance URL configuration
- search box
- filters
- result cards
- skill preview
- copy/export actions
- feedback buttons
- context visibility indicator

### Authentication

Use browser-based OAuth or device authorization flow against the organization's OIDC provider (configured on the Alexandria instance).

Flow:

```text
User configures Alexandria instance URL
  ↓
User clicks Sign in
  ↓
Browser opens organization's identity provider
  ↓
User authenticates with SSO
  ↓
Extension receives token
  ↓
Token stored in VS Code SecretStorage
```

### Context payload example

```json
{
  "repository": {
    "name": "checkout-service",
    "detected_languages": ["typescript"],
    "detected_frameworks": ["nestjs"],
    "dependency_names": ["@nestjs/core", "jest", "typeorm"],
    "package_managers": ["npm"]
  },
  "workspace": {
    "active_file_path": "src/payment/payment.controller.ts",
    "active_file_language": "typescript",
    "selected_text": null,
    "selected_text_allowed": false
  }
}
```

### Local file export

When adding a skill to a repository file:

1. show preview
2. explain destination
3. require confirmation
4. insert marked section
5. track export event

Example:

```markdown
<!-- alexandria:start skill.secure-rest-endpoint version=2.3.1 -->
...
<!-- alexandria:end -->
```

This makes future update detection possible.

## CLI

### CLI commands

```bash
alexandria login --instance https://alexandria.internal.company.com
alexandria whoami
alexandria search "query"
alexandria search "query" --tool codex --language java --framework spring
alexandria show <skill>
alexandria export <skill> --tool copilot
alexandria add <skill> --target agents-md
alexandria feedback <skill> --worked
alexandria configure
```

### Example output

```text
Found 5 skills

1. Kafka Consumer Integration Test Workflow
   Company approved · Java · Spring Boot · Kafka · Copilot/Codex
   Why: matched Kafka, integration tests, Maven project, Java repo.

2. Spring Boot Testcontainers Setup
   Team approved · Java · Spring Boot · Testcontainers
   Why: matched test generation and dependency pattern.
```

### CLI implementation

Use Go for stable cross-platform binaries.

The CLI connects to the organization's deployed Alexandria instance (configurable URL stored in `~/.alexandria/config.yaml`).

## Browser catalog improvements

Phase 2 should improve the existing web app.

Add:

- hybrid relevance
- "why this result"
- matched tags
- tool compatibility badges
- warning badges
- result grouping by task type
- saved searches
- skill collections
- related skills

## Skill relationships

Add basic relationships:

```text
related_to
supersedes
superseded_by
derived_from
conflicts_with
recommended_with
alternative_to
```

Schema:

```sql
CREATE TABLE skill_relationships (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  source_skill_id UUID NOT NULL REFERENCES skills(id),
  target_skill_id UUID NOT NULL REFERENCES skills(id),
  relationship_type TEXT NOT NULL,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (source_skill_id, target_skill_id, relationship_type)
);
```

## Repository profiles

Phase 2 can store lightweight repository profiles.

```sql
CREATE TABLE repository_profiles (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  provider TEXT,
  external_repo_id TEXT,
  name TEXT NOT NULL,
  default_branch TEXT,
  owner_team_id UUID,
  languages TEXT[] NOT NULL DEFAULT '{}',
  frameworks TEXT[] NOT NULL DEFAULT '{}',
  build_tools TEXT[] NOT NULL DEFAULT '{}',
  package_managers TEXT[] NOT NULL DEFAULT '{}',
  metadata JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (organization_id, provider, external_repo_id)
);
```

Repository profiles can be created from:

- IDE context
- CLI context
- manual admin entry

## Search and recommendation events

### Search events

```sql
CREATE TABLE skill_search_events (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  user_id UUID,
  source TEXT NOT NULL,
  query TEXT NOT NULL,
  context_summary JSONB NOT NULL DEFAULT '{}',
  filters JSONB NOT NULL DEFAULT '{}',
  result_count INTEGER NOT NULL,
  clicked_skill_id UUID,
  created_at TIMESTAMPTZ NOT NULL
);
```

### Recommendation events

```sql
CREATE TABLE skill_recommendation_events (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  user_id UUID,
  source TEXT NOT NULL,
  skill_id UUID NOT NULL,
  skill_version_id UUID NOT NULL,
  rank INTEGER NOT NULL,
  score NUMERIC NOT NULL,
  features JSONB NOT NULL DEFAULT '{}',
  clicked BOOLEAN DEFAULT false,
  copied BOOLEAN DEFAULT false,
  feedback TEXT,
  created_at TIMESTAMPTZ NOT NULL
);
```

These events will become training data for Phase 3.

## Context levels

```text
Level 0: no repository context
Level 1: metadata only (default)
Level 2: structural context
Level 3: selected text with explicit permission
Level 4: full repository indexing, deferred
```

### Level 1 metadata

Allowed:

- repository name
- branch name
- language
- framework
- package manager
- active file path
- file extension
- preferred AI tool

### Level 2 structural context

Allowed:

- dependency names
- project structure summary
- filenames
- imports
- class names
- function names
- test framework detection

No function bodies.

### Level 3 selected text

Allowed only when:

- admin policy permits it
- user explicitly triggers it
- sensitive file detection passes

All context stays within the organization's network — the extension sends context to the organization's Alexandria instance, not to any external service.

## Sensitive file protection

Never send content from:

```text
.env
*.pem
*.key
secrets.*
credentials.*
kubeconfig
```

The extension and CLI should block this locally before any API call.

## Admin controls

### Embedding provider settings

```json
{
  "embedding": {
    "provider": "bedrock",
    "model": "amazon.titan-embed-text-v2",
    "dimensions": 1536
  }
}
```

### IDE context policy

```json
{
  "ide_context_policy": {
    "allow_repository_name": true,
    "allow_file_paths": true,
    "allow_dependency_names": true,
    "allow_symbol_names": true,
    "allow_selected_text": false,
    "allow_terminal_output": false
  }
}
```

### Tool settings

- enabled tools
- disabled tools
- default tool by team
- allowed export formats

### Taxonomy settings

- required tags
- synonyms
- deprecated tags
- custom tag permissions

## Phase 2 implementation milestones

### Milestone 1: Search index upgrade

Build:

- normalized search document
- improved full-text search
- filters
- deterministic ranking
- search analytics

Deliverable:

> Web search becomes significantly better than Phase 1.

### Milestone 2: Embeddings and hybrid retrieval

Build:

- embedding provider abstraction
- embedding pipeline
- pgvector index
- hybrid retrieval
- candidate merging
- search quality dashboard

Deliverable:

> Natural-language search works for semantically related skills.

### Milestone 3: Context model

Build:

- repository context schema
- context normalization
- language/framework detection
- admin context policy

Deliverable:

> Backend can safely use coding context in search.

### Milestone 4: VS Code extension

Build:

- instance URL configuration
- login (OIDC flow)
- search
- context-aware search
- result preview
- copy/export
- feedback

Deliverable:

> Developers can discover and reuse skills from inside VS Code.

### Milestone 5: CLI

Build:

- instance URL configuration
- login
- search
- show
- export
- add to local files
- feedback

Deliverable:

> Power users and platform teams can use Alexandria from terminals and scripts.

### Milestone 6: Explanations and relationships

Build:

- why recommended
- related skills
- alternatives
- superseded skill handling

Deliverable:

> Developers understand and trust recommendations.

### Milestone 7: Admin controls and analytics

Build:

- IDE context policy
- embedding provider configuration UI
- tool settings
- search quality dashboard
- adoption dashboard

Deliverable:

> Enterprise admins can govern workflow integration.

## MVP cut line

If scope must be reduced, keep:

1. improved full-text search
2. vector search (pgvector)
3. deterministic hybrid ranking
4. VS Code extension
5. metadata-only context
6. copy/export from IDE
7. search/result analytics
8. deterministic explanations

Defer:

- JetBrains plugin
- selected code upload
- advanced query expansion
- skill relationships
- saved searches
- repository profiles
- CLI
- placeholder templating

If choosing between VS Code and CLI, choose VS Code first unless target users are heavily terminal-based.

## Phase 2 success metrics

### Adoption

- weekly active users in IDE extension
- searches per active user
- percentage of searches from IDE
- repeat usage rate
- number of teams using the extension

### Discovery quality

- search click-through rate
- copy/export rate
- zero-result rate
- explicit not-relevant rate
- Precision@5 on labeled query set

### Reuse

- number of skill exports
- skills added to local repository instruction files
- skills favorited
- team collections created

### Governance

- percentage of copied skills that are approved
- high-risk skill exposure rate
- stale skill result rate

### Performance

- p95 search latency
- p95 IDE search latency
- embedding job completion time
- API error rate

### Community

- VS Code extension installs
- CLI downloads
- community-contributed embedding providers
- bug reports and feature requests

## Phase 2 risks and mitigations

### Risk: Search feels mediocre

Mitigation:

- hybrid retrieval
- search evaluation set
- query analytics
- controlled synonyms
- tag quality improvements
- zero-result analysis

### Risk: IDE extension setup is confusing

Mitigation:

- clear "configure instance URL" flow
- auto-detect from workspace settings
- good error messages when instance is unreachable
- documentation with screenshots

### Risk: Embedding provider adds complexity

Mitigation:

- Ollama option for zero-external-dependency setup
- AWS Bedrock as recommended default for AWS shops
- clear configuration documentation
- graceful fallback to keyword-only search if embeddings are not configured

### Risk: Recommendations are not trusted

Mitigation:

- approval badges
- owner visibility
- usage signals
- deterministic explanations
- warnings and limitations

### Risk: Too many irrelevant skills

Mitigation:

- filter by approval state
- boost team and framework matches
- hide deprecated skills
- penalize context mismatches

### Risk: Integration scope slows delivery

Mitigation:

- VS Code first
- clipboard and file export first
- defer JetBrains and deep tool integrations

## Phase 2 thesis

Phase 2 succeeds if developers stop treating Alexandria as a website and start treating it as part of their coding environment.

The most important capability is:

> context-aware discovery of trusted, reusable AI engineering workflows at the moment the developer needs them.

This is the bridge between a governed skill catalog and the larger Alexandria vision — still fully open source, still fully self-hosted, still fully within the organization's security boundary.
