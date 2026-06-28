---
inclusion: auto
---

# Service Implementation Guide

## Service Creation Steps

1. **API interface** (`alexandria-api`) — define `{Domain}Api` with Spring Web annotations and Javadoc
2. **DTOs** (`alexandria-api`) — define request/response records with Jakarta validation
3. **Entity** (`alexandria-entities`) — define `{Domain}Entity` with JPA annotations
4. **Repository** (`alexandria-entities`) — define `{Domain}Repository` extending Spring Data JPA
5. **Service interface** (`alexandria-service`) — define `{Domain}Service` contract with Javadoc
6. **Service implementation** (`alexandria-service`) — `@Service`, `@RequiredArgsConstructor`, business logic
7. **Resource** (`alexandria-service`) — `@RestController` implementing `{Domain}Api`, `@PreAuthorize`, delegates to service

## Service Template

```java
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillRepository repository;
    private final SkillValidator validator;

    @Transactional(readOnly = true)
    @Override
    public SkillResponse get(@NonNull UUID organizationId, @NonNull UUID skillId) {
        validator.validateGetInputs(organizationId, skillId);
        SkillEntity entity = repository.findByOrganizationIdAndId(organizationId, skillId)
            .orElseThrow(() -> new SkillNotFoundException(skillId));
        return SkillMapper.toResponse(entity);
    }

    @Transactional
    @Override
    public SkillResponse create(@NonNull UUID organizationId, @NonNull CreateSkillRequest request) {
        validator.validateCreateInputs(organizationId, request);
        SkillEntity entity = SkillMapper.toEntity(organizationId, request);
        SkillEntity saved = repository.saveAndFlush(entity);
        return SkillMapper.toResponse(saved);
    }
}
```

## Resource Template (alexandria-service, implements API interface)

```java
@RestController
@RequiredArgsConstructor
public class SkillResource implements SkillApi {

    private final SkillService service;

    @GetMapping("/{skillId}")
    @PreAuthorize("hasPermission(#skillId, 'skill', 'VIEW')")
    public SkillResponse get(@PathVariable UUID skillId) {
        UUID orgId = SecurityContextUtils.getOrganizationId();
        return service.get(orgId, skillId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('skill:create')")
    public SkillResponse create(@Valid @RequestBody CreateSkillRequest request) {
        UUID orgId = SecurityContextUtils.getOrganizationId();
        return service.create(orgId, request);
    }
}
```

## Rules

- Business logic lives in Service, never in Resource
- Always validate inputs in the service layer
- Always use `@Transactional` — `readOnly = true` for queries
- Always scope queries by `organizationId`
- Log audit events for governance actions (create, update, publish, approve, delete)
- Handle `DataIntegrityViolationException` for unique constraint violations
- Use mappers (static methods or dedicated class) to convert between entity/DTO — not Lombok @Builder chains in resources
