---
inclusion: auto
---

# Project Structure

## Repository Layout

```
alexandria/
├── .kiro/
│   ├── steering/                          # AI steering rules
│   ├── 01_product_vision.md               # Product vision
│   ├── 02_architecture_choices.md         # Architecture decisions
│   ├── 03_phase_1_*.md                    # Phase 1 spec
│   └── 04_phase_2_*.md                    # Phase 2 spec
├── backend/
│   ├── alexandria-api/                    # REST API contracts
│   │   └── src/main/java/com/alexandria/
│   │       └── {module}/
│   │           ├── dto/                   # Request/Response records
│   │           └── {Domain}Api.java       # Resource interface
│   ├── alexandria-entities/               # JPA entities & repositories
│   │   └── src/main/java/com/alexandria/
│   │       └── {module}/
│   │           ├── entity/                # JPA entities
│   │           └── {Domain}Repository.java
│   ├── alexandria-service/                # Business logic & resource implementations
│   │   └── src/main/java/com/alexandria/
│   │       └── {module}/
│   │           ├── model/                 # Business objects
│   │           ├── {Domain}Resource.java  # Implements {Domain}Api
│   │           ├── {Domain}Service.java   # Interface
│   │           └── {Domain}ServiceImpl.java
│   └── pom.xml                            # Parent POM
├── frontend/                              # Next.js + TypeScript
│   ├── src/
│   │   ├── app/                           # Next.js app router
│   │   ├── components/                    # React components
│   │   └── lib/                           # Client utilities & API client
│   └── package.json
├── cli/                                   # Go CLI (Phase 2)
├── extension/                             # VS Code extension (Phase 2)
├── infrastructure/                        # Terraform/Pulumi IaC
└── docker-compose.yml                     # Local dev environment
```

## Backend Module Dependency

```
alexandria-api          ← no internal deps, pure contracts
alexandria-entities     ← no internal deps, pure data layer
alexandria-service      ← depends on alexandria-api + alexandria-entities
```

## Module Responsibilities

### alexandria-api
- Resource interfaces (Java interfaces annotated with Spring Web annotations)
- Request/Response DTOs (Java records with Jakarta validation)
- Shared constants and enums used in API contracts
- No implementation logic, no Spring beans

### alexandria-entities
- JPA entities (`@Entity` classes)
- Spring Data JPA repositories
- Database migration scripts (Flyway)
- No business logic, no HTTP concerns

### alexandria-service
- Resource implementations (`@RestController` classes implementing API interfaces)
- Service interfaces and implementations (business logic)
- Business objects / domain models
- Validators, mappers, configuration
- Background job handlers

## Design Rules

- All tenant-owned entities include `organizationId` field
- All repository queries filter by `organizationId`
- ACL enforcement happens before search results are returned
- Audit events logged for every governance action
- Sensitive content scanning on skill create/update
- Markdown output sanitized to prevent XSS
