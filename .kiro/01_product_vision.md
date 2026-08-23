# Alexandria Product Vision

## One-line positioning

**Alexandria is the open-source enterprise skill layer for AI-assisted software engineering: a governed, searchable, evidence-ranked registry of reusable AI coding workflows that organizations deploy on their own AWS infrastructure.**

## Executive summary

Engineering teams are rapidly adopting AI coding tools such as GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, Sourcegraph Cody, and internal agents. The problem is no longer whether developers will use AI. The problem is that every team is learning in isolation.

The best prompts, instructions, agent workflows, review checklists, architectural heuristics, testing strategies, and security practices are scattered across personal notes, Slack threads, repositories, wiki pages, IDE settings, and tool-specific configuration files. Teams repeatedly rediscover the same patterns, duplicate effort, and spread inconsistent practices. Some workflows are effective, but hard to find. Others are outdated, unsafe, or incompatible with a given codebase.

Alexandria turns those fragmented practices into governed, reusable engineering assets. It lets teams publish skills, discover skills created by others, understand when a skill applies, export it into their AI coding tool of choice, and eventually measure whether it actually improves outcomes.

The long-term vision is not a prompt library. It is a **skill intelligence platform** for software engineering organizations — one that runs entirely within their security boundary.

## Why open source and self-hosted

The market is not ready for a SaaS product in this space yet. Enterprises exploring AI coding governance are still evaluating whether this is a real problem worth paying for. Meanwhile, the concerns that block SaaS adoption are very real:

### Security and confidentiality

AI coding workflows often contain proprietary architecture decisions, security patterns, internal service names, and codebase-specific knowledge. Organizations are reluctant to store this in a third-party SaaS, especially when the content could reveal internal system design.

### Early market timing

The category does not exist yet. An open-source approach lets enterprises try the solution without procurement overhead, budget approval, or vendor risk assessment. This accelerates validation and community building.

### Trust through transparency

Open source means organizations can audit the code, verify data handling, and contribute back. This builds the kind of trust that a closed SaaS cannot achieve at this stage.

### Community-driven development

The best skill governance patterns will emerge from real enterprise usage. An open community can contribute integrations, export formats, ranking strategies, and deployment patterns faster than a single team.

### Path to commercial sustainability

The open-source core provides value immediately. Future commercial opportunities include:

- Enterprise support and SLA
- Managed hosting (once the market matures)
- Premium features (advanced analytics, compliance certifications, managed upgrades)
- Consulting and implementation services
- Marketplace for curated skill collections

## The problem

Modern engineering organizations face five compounding issues.

### 1. AI knowledge is fragmented

Different teams use different tools and conventions:

- GitHub Copilot instructions
- Cursor rules
- Codex task prompts
- AWS Kiro specs and steering files
- `AGENTS.md`
- internal assistant prompts
- code review checklists
- architecture review templates
- testing playbooks
- migration workflows

Even when one team creates a strong workflow, other teams rarely discover or reuse it.

### 2. Best practices are not operationalized

A good AI workflow is often more than a prompt. It may include:

- task framing
- repository inspection steps
- tool-specific instructions
- output constraints
- examples
- anti-patterns
- architecture rules
- security constraints
- evaluation checklists
- review criteria

Most organizations lack a way to package this as a reusable asset.

### 3. Existing tools are siloed

Most AI coding tools offer their own customization model. That is useful, but creates silos.

A team may write strong Copilot instructions, while another team writes Cursor rules, while another uses Kiro specs. There is no neutral layer that lets the organization discover and reuse the best practices across tools.

### 4. Trust is missing

Developers will not blindly reuse a prompt or workflow from another team unless they know:

- who owns it
- when it was last updated
- whether it was approved
- whether it applies to their stack
- whether it is safe
- whether it has worked before
- what its limitations are

Without trust signals, a catalog becomes a dumping ground.

### 5. Enterprises need governance

AI coding workflows can influence security, architecture, reliability, and compliance. Enterprises need:

- access control
- ownership
- approval workflows
- versioning
- auditability
- deprecation
- risk classification
- usage visibility
- policy enforcement

A simple shared prompt folder cannot satisfy those requirements.

## The opportunity

The opportunity is to create a vendor-neutral, self-hosted layer between engineering teams and the AI tools they use.

```text
Developers and teams
        ↓
Alexandria (deployed on customer AWS)
        ↓
Copilot, Codex, Cursor, Kiro, Claude Code, Cody, internal agents, CI bots
```

Alexandria does not need to replace coding assistants. It should make every coding assistant more useful by providing trusted, reusable, context-aware engineering skills — all within the organization's security boundary.

## Target customers

### Primary adopters

- VP Engineering
- CTO
- Head of Developer Productivity
- Head of Platform Engineering
- Head of AI Transformation
- Engineering Operations leaders
- Enterprise Architecture leaders
- Security Engineering leaders

### Primary users

- software developers
- tech leads
- staff engineers
- architects
- platform engineers
- security champions
- developer productivity teams
- enablement teams

### Early adopter profile

The strongest early adopters will likely have:

- 100+ developers
- active usage of multiple AI coding tools
- strong platform or developer productivity function
- repeated internal discussions about AI coding governance
- many teams solving similar software engineering problems
- pressure to show measurable ROI from AI coding tools
- preference for self-hosted solutions due to security requirements
- existing AWS infrastructure

## User personas

### Developer

Wants to complete a coding task faster without leaving the IDE.

Needs:

- relevant skill discovery
- clear trust indicators
- one-click copy or export
- minimal friction
- no compliance overhead

### Skill author

Has created a useful AI workflow and wants others to benefit from it.

Needs:

- easy publishing
- structured but lightweight metadata
- examples
- tool-specific variants
- ownership and versioning

### Staff engineer or architect

Wants teams to reuse proven technical practices.

Needs:

- review workflow
- approval state
- curated collections
- architecture guidance
- deprecation controls

### Security reviewer

Wants to prevent unsafe AI workflows from spreading.

Needs:

- risk labels
- high-risk review queues
- secret detection
- policy warnings
- audit trail

### Platform owner

Wants adoption, governance, and visibility.

Needs:

- analytics
- SSO
- teams and permissions
- admin policies
- integration management
- export controls

### Deployer

Responsible for installing and maintaining Alexandria on the organization's AWS infrastructure.

Needs:

- clear deployment documentation
- Infrastructure-as-Code templates
- simple upgrade path
- configuration through environment variables
- health checks and observability
- backup and recovery procedures

## Product concept

A **skill** is a reusable AI engineering workflow.

A skill can contain:

- canonical instructions
- prompts
- agent workflows
- tool-specific variants
- code generation templates
- architecture review checklists
- test-generation strategies
- evaluation rubrics
- examples of successful outputs
- guardrails
- applicability rules
- limitations
- risk classification
- ownership and approval state

A skill is not just a document. It is a versioned, governed, searchable asset.

## Core product capabilities

### 1. Skill registry

A central place to create, version, govern, and publish AI engineering skills.

Capabilities:

- structured skill editor
- ownership
- lifecycle state
- visibility controls
- version history
- review and approval
- risk classification
- examples and limitations

### 2. Skill discovery

A search experience that helps developers find the right skill.

Capabilities:

- keyword search
- semantic search
- filters
- task-type classification
- language and framework filtering
- tool compatibility filtering
- approval and trust signals
- "why this matched" explanations

### 3. Tool portability

A canonical skill can be exported into multiple tool formats.

Supported targets can include:

- generic Markdown
- GitHub Copilot prompt or instruction fragment
- Codex task instructions
- `AGENTS.md`
- Cursor rules
- AWS Kiro steering/spec fragments
- Sourcegraph Cody prompts
- internal agent instructions

### 4. Governance

Enterprises can manage skills as operational assets.

Capabilities:

- RBAC
- SSO
- team ownership
- approval workflows
- audit logs
- risk levels
- deprecation
- expiration
- restricted visibility
- high-risk review flows

### 5. Workflow integration

Developers can access skills inside their daily tools.

Initial integration surfaces:

- web app
- VS Code extension
- CLI
- later JetBrains plugin
- later CI and PR integrations

### 6. Usage feedback

The system collects basic signals that become the foundation for ranking.

Signals:

- search
- view
- copy
- export
- favorite
- feedback
- "not relevant"
- "outdated"
- "worked well"
- "unsafe"

### 7. Future skill intelligence

Over time, Alexandria can become an intelligent layer that recommends, adapts, validates, and composes skills.

Future capabilities:

- repo-aware recommendations
- skill adaptation to local codebase patterns
- learning-to-rank
- drift detection
- automated evaluation
- skill composition
- managed execution through tool adapters

## Product principles

### Open source by default

The core platform is open source under a permissive license. This builds trust, encourages contribution, and removes procurement friction for early adopters.

### Self-hosted for security

All data stays within the customer's AWS account. No external data transfer, no shared infrastructure, no vendor access to content.

### Vendor-neutral by design

The product should not depend on one AI coding assistant. It should help organizations reuse skills across current and future tools.

### Governance without bureaucracy

Publishing a draft skill should be easy. Company-wide approval should be more rigorous. Use progressive enrichment rather than forcing heavy metadata upfront.

### Trust as a first-class feature

Search results should show owner, approval state, freshness, tool compatibility, risk, and usage signals.

### Context-aware, privacy-aware

The system should use coding context where helpful, but source code should not be uploaded by default. Metadata-first context collection is the safest path.

### Skills are assets, not snippets

Skills need versioning, ownership, lifecycle, review, and observability.

### Integrate where developers work

A catalog alone is insufficient. The product must enter IDEs, CLIs, repositories, and later CI/PR workflows.

### Simple to deploy and operate

The platform should be deployable with minimal operational expertise. Clear documentation, IaC templates, and sensible defaults reduce the barrier to adoption.

## Differentiation

Alexandria is differentiated from prompt libraries because it adds:

- governance
- ownership
- versioning
- approval
- access control
- tool portability
- search relevance
- context awareness
- usage telemetry
- trust scoring
- future skill adaptation and composition

It is differentiated from AI coding assistants because it does not replace them. It sits above them as a shared enterprise knowledge layer.

It is differentiated from LLMOps platforms because it focuses on software engineering teams reusing AI workflows in coding environments, not only on managing prompts for production LLM applications.

It is differentiated from SaaS alternatives because it runs entirely within the customer's infrastructure, giving full control over data, security, and compliance.

## Community and adoption strategy

### Open-source community building

- GitHub repository with clear contribution guidelines
- Public roadmap and issue tracking
- Community discussions and feature requests
- Regular releases with changelog
- Example skill libraries and starter content
- Deployment guides for various AWS configurations

### Adoption path

1. Developer discovers Alexandria through GitHub, blog posts, or community
2. Platform team evaluates by reading docs and trying local Docker Compose setup
3. Organization deploys on their AWS account using provided Terraform templates
4. Teams onboard and begin publishing skills
5. Adoption grows through internal word-of-mouth as developers find useful skills
6. Organization contributes back improvements or integrations

### Go-to-market

- Open-source launch with strong documentation
- Blog posts explaining the problem space
- Conference talks at developer productivity events
- Partnerships with AI coding tool vendors
- Community Slack or Discord for adopters
- Case studies from early adopters

## Ideal customer discovery questions

Use these when talking to potential adopters.

### Current state

1. Which AI coding tools are your teams using today?
2. Do different teams use different tools or conventions?
3. Where do developers currently store prompts, instructions, and AI workflows?
4. How do teams share successful AI coding practices?
5. Are there team-specific or company-wide AI coding standards?

### Pain

6. Are teams duplicating AI prompt or workflow work?
7. Do developers know which AI workflows are trusted?
8. Have you seen unsafe, outdated, or low-quality AI-generated practices spread?
9. Are engineering leaders able to measure AI coding adoption and effectiveness?
10. Do security or architecture teams review AI coding workflows today?

### Workflow

11. Would developers use a skill catalog in the browser?
12. Would IDE integration be necessary for adoption?
13. Which IDEs and AI coding tools matter most?
14. Would copy/export be enough initially?
15. What repository or code context would you allow the system to use?

### Governance

16. Who should approve company-wide skills?
17. Which skills would require security review?
18. Do you need private, team-only, or restricted skills?
19. Would you need audit logs for AI workflow changes?
20. What are your security requirements for hosting solutions?

### Deployment

21. Do you have existing AWS infrastructure?
22. What container orchestration do you use (ECS, EKS, other)?
23. How do you manage database infrastructure?
24. What identity provider do you use?
25. Do you have a platform engineering team that could deploy and maintain this?

## Value proposition by adopter role

### VP Engineering

- faster AI adoption
- reuse of best practices
- reduced duplication
- visibility into usage
- improved engineering consistency
- data stays in-house

### Developer Productivity leader

- central platform for AI coding enablement
- reusable workflows
- measurable adoption
- integration into IDEs and CLIs
- no vendor lock-in

### Security leader

- all data within security boundary
- control over risky AI workflows
- approval gates
- auditability
- restricted access
- secret and unsafe instruction scanning
- full code auditability (open source)

### Architect or Staff Engineer

- reusable architecture guidance
- company-approved patterns
- deprecation of outdated practices
- standardization without blocking teams

### Developer

- faster access to useful prompts and workflows
- less trial and error
- trusted examples from other teams
- export into preferred coding assistant

### Platform / DevOps engineer

- IaC deployment templates
- standard AWS services
- simple operational model
- clear upgrade path

## Product roadmap overview

### Phase 1: Governed Skill Registry

Build the foundation:

- skill creation
- versioning
- metadata
- ownership
- search
- manual copy/export
- approval workflow
- feedback
- audit logs
- self-hosted deployment on AWS
- IaC templates

### Phase 2: Hybrid Search and Workflow Integration

Bring the catalog into developer workflows:

- hybrid keyword and semantic search
- IDE extension
- CLI
- context-aware search
- tool-aware export
- explanations
- search analytics

### Phase 3: Ranking and Telemetry

Use real signals to improve quality:

- trust scoring
- deterministic and ML-assisted ranking
- team affinity
- usage-based recommendations
- stronger analytics
- stale-skill indicators

### Phase 4: Skill Compiler and Adapters

Make skills portable across tools:

- canonical skill format
- richer tool-specific compilation
- Codex/Copilot/Cursor/Kiro adapters
- managed export
- repository instruction management

### Phase 5: Contextual Adaptation and Composition

Make the platform intelligent:

- LLM-assisted adaptation
- policy-aware context binding
- multi-skill workflows
- drift detection
- evaluation harness
- managed skill execution

## What success looks like

Within early adopters:

- teams publish real skills
- developers search from the catalog or IDE
- developers reuse skills through copy/export
- approved skills are copied more often than unapproved skills
- teams begin curating collections
- platform owners can identify popular, stale, or low-quality skills
- deployers find setup straightforward with provided IaC

Within a mature deployment:

- every major engineering team has approved skills
- developers search from IDEs as part of normal flow
- common engineering workflows are standardized through reusable skills
- security-sensitive skills have review gates
- skill usage data informs training, governance, and investment decisions
- the platform becomes the shared memory for how the organization uses AI in software engineering
- community contributions improve the platform for all adopters

## Core thesis

The next wave of AI developer productivity will not come only from better models or better editors. It will come from organizations learning how to reuse their best AI-assisted engineering practices at scale.

Alexandria is the open-source platform for that organizational learning — deployed where the data belongs: inside the enterprise.
