---
inclusion: auto
---

# Java Coding Standards

## Architecture: Strict Layers (across modules)

1. **API** (`alexandria-api`) — Resource interfaces, DTOs (records), validation annotations
2. **Entity** (`alexandria-entities`) — JPA entities, Spring Data repositories, migrations
3. **Service** (`alexandria-service`) — Resource implementations, business services, domain models

## Naming Conventions

- Resources: `{Domain}Resource` (in `alexandria-service`, implements `{Domain}Api`)
- API interfaces: `{Domain}Api` (in `alexandria-api`)
- DTOs: `{Action}{Domain}Request`, `{Domain}Response` (in `alexandria-api`)
- Services: `{Domain}Service` + `{Domain}ServiceImpl` (in `alexandria-service`)
- Repositories: `{Domain}Repository` (in `alexandria-entities`)
- Entities: `{Domain}Entity` (in `alexandria-entities`)
- Business objects: `{Domain}` or `{Domain}Model` (in `alexandria-service`)
- Exceptions: `{Description}Exception`

Package: `com.alexandria.{module}` (one package per domain module)

## Code Style

### Lombok
- `@RequiredArgsConstructor` for DI
- `@NonNull` for null checks on parameters
- `@Builder` for complex object construction
- Avoid `@Data` — use explicit `@Getter`/`@ToString` where needed

### Dependency Injection
- Constructor injection via `@RequiredArgsConstructor`
- All dependencies `private final`
- Never use field injection (`@Autowired`)

### Records
- Use Java records for DTOs, value objects, and immutable data
- Use records for API request/response models

### Transactions
- `@Transactional(readOnly = true)` for reads
- `@Transactional` for writes
- Keep transactions as short as possible

## Organization Scoping

- Every service method that accesses data must receive or resolve `organizationId`
- Repository methods must filter by `organizationId`
- This convention enables future flexibility (multi-instance management, data export) even in single-org deployments

## Documentation

- Javadoc required for public interfaces and service methods
- Include `@param`, `@return`, `@throws`
