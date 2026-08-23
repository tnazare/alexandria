---
inclusion: auto
---

# Testing Guidelines

## Coverage Target

100% branch and line coverage (JaCoCo).

## Test Layers

| Layer | Tools | Mocking | Purpose |
|-------|-------|---------|---------|
| Unit | JUnit 5, Mockito, AssertJ | Mock all deps | Service logic, validators, mappers |
| Resource | MockMvc, @WebMvcTest | Mock service layer | HTTP, auth, status codes, validation |
| Integration | @SpringBootTest, Testcontainers | Real DB | Full flows, tenant isolation, search |

## Unit Test Structure

```java
@ExtendWith(MockitoExtension.class)
class SkillServiceImplTest {

    @Mock private SkillRepository repository;
    @Mock private SkillValidator validator;
    @InjectMocks private SkillServiceImpl service;

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("returns skill when found")
        void returnsSkillWhenFound() {
            when(repository.findByOrganizationIdAndId(orgId, skillId))
                .thenReturn(Optional.of(entity));

            SkillResponse result = service.get(orgId, skillId);

            assertThat(result.id()).isEqualTo(skillId);
            verify(validator).validateGetInputs(orgId, skillId);
        }

        @Test
        @DisplayName("throws when skill not found")
        void throwsWhenNotFound() {
            when(repository.findByOrganizationIdAndId(orgId, skillId))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(orgId, skillId))
                .isInstanceOf(SkillNotFoundException.class);
        }
    }
}
```

## Conventions

- `@Nested` for grouping by method
- `@DisplayName` on every test — human-readable description
- AAA pattern: Arrange → Act → Assert
- AssertJ for all assertions (never JUnit `assertEquals`)
- Verify mock interactions explicitly
- Use `@ParameterizedTest` for variations of the same scenario
- Separate test data into constants or factory methods

## Security Tests (Critical)

- Private skills must not appear in unauthorized users' search results
- Visibility rules must be enforced (private, team, organization, restricted)
- Unauthorized approval/review attempts must fail
- Sensitive content scanning must block secrets in skill content
- RBAC enforcement must prevent privilege escalation

## Search Relevance Tests

- Maintain an evaluation set of queries and expected results
- Track: Precision@5, MRR, zero-result rate
