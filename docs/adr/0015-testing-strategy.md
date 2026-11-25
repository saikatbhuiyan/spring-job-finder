# ADR 0015: Testing strategy

- **Status:** Accepted
- **Date:** 2026-11-25
- **Extends:** [ADR 0001](0001-modular-clean-architecture.md) (how each layer is tested),
  [ADR 0008](0008-exception-handling-hardening.md) (unparseable query strings are a `400`),
  [ADR 0014](0014-idempotency-keys.md) (`IdempotencyConfig` split)

## Context

Job Finder had 218 tests: unit tests, `ApplicationContextRunner` configuration tests, architecture
tests, a packaged-app test in a child JVM, and 12 `@SpringBootTest(RANDOM_PORT)` classes over real HTTP.
There were no slice tests, no mocks, and no measure of test quality beyond hand-made mutation checks.
Feature modules (job, company, application) come next, each with controllers, use cases and
repositories, so how each layer is tested had to be decided before they multiply. Measured
(`learning/40-testing-rest-apis.md`):

- **No `@WebMvcTest` could start.** `IdempotencyConfig` implemented `WebMvcConfigurer` (to register its
  interceptor), so every MVC slice loaded it, and its filter bean needs a `MeterRegistry`, which isn't
  in the slice: `UnsatisfiedDependencyException` for any controller slice test.
- **Once it could, the slice accepted `{"title": 123}`** as the string `"123"`; the application answers
  `400 InvalidType` (ADR 0009). `JsonBindingConfig` is a plain `@Configuration`, which slices don't scan.
- **Mockito attached itself at runtime**: the JDK warns that this "will no longer work in future
  releases" (JEP 451), which would break the build on a JDK upgrade.
- **Coverage**: 91.7 % of lines and 82.5 % of branches. Untested: `@PathVariable` and `@RequestHeader`
  constraints (the most common constraints in a REST API).
- **Mutation testing** (PIT, 409 mutants, from clean): 79 % killed, test strength 84 %. Survivors
  included real gaps (boundaries never tested, an assertion on the status only, metrics never checked)
  and **false survivors**: PIT swaps
  mutants into one JVM, so Spring's cached contexts keep results computed by earlier code
  (per-handler caches, recorded meters); applied by hand, those mutants fail 30 tests.
- **A malformed query string was a logged `500`** (found while writing those tests): Tomcat 11 throws
  `InvalidParameterException` for `?=x`, `?q=%zz`, `?q=%`, `?q=%E9` or more than 1,000 parameters when
  Spring MVC binds the first parameter, and nothing mapped it. Now a `400` Problem Details without
  Tomcat's message (it quotes the raw input), in `GlobalExceptionHandler` (ADR 0008: client errors are
  never `5xx`).
- **Speed**: the suite builds 15 contexts (1,317 cache hits) in ~20 s. With a warm JVM a full
  `RANDOM_PORT` context starts in ~0.15 s and a `@WebMvcTest` slice in ~0.18 s: in an application this
  small, slices are not faster. Their value is isolation (use cases mocked; later, no database needed).

## Decision

1. **The lowest kind of test that can see the behavior**, per layer:

   | What | Kind of test | Tools |
   | --- | --- | --- |
   | Domain rules, value objects, parsers | Unit, no Spring | JUnit, AssertJ; fakes over mocks; injected clocks |
   | Use cases (`application`) | Unit with mocks of the module's ports | Mockito (`@ExtendWith(MockitoExtension.class)`) |
   | `@ConfigurationProperties`, conditional beans | Configuration | `ApplicationContextRunner` |
   | Wire format of DTOs | JSON slice | `@JsonTest`, `JacksonTester` |
   | Each controller | Web slice, use cases mocked | `@WebMvcTest`, `MockMvcTester`, `@MockitoBean` |
   | Filters, error controller, CORS, cross-cutting contracts, end-to-end flows | Integration over real HTTP | `@SpringBootTest(RANDOM_PORT)`, `RestTestClient` |
   | Repositories, queries, migrations (with PostgreSQL) | Persistence slice on the real database | `@DataJpaTest`, Testcontainers `@ServiceConnection`; **never H2** |
   | Structure | Architecture | ArchUnit, Spring Modulith, with violating fixtures |
   | Profiles, packaging | Packaged app in a child JVM | `ProfileConfigurationTests` |

   Mocks only at boundaries: use cases in controller slices, ports in use-case tests, never values or
   types we don't own (`JdbcTemplate`, `HttpClient`): those get tested against the real thing.
2. **Slices run the application's API contract.** A `WebMvcConfigurer` (or any type slices scan)
   contains Spring MVC configuration only; servlet filters and their beans live in a separate
   `@Configuration` (hence `IdempotencyKeyConfig` beside `IdempotencyConfig`). Application configuration a
   slice needs but doesn't scan is listed in the slice's `.imports` file under `src/test/resources`
   (`META-INF/spring/org.springframework.boot.test.autoconfigure.json.AutoConfigureJson.imports`:
   `JsonBindingConfig`, for `@JsonTest` and `@WebMvcTest`). Locked in by `WebMvcSliceTests` and
   `JsonSliceTests`.
3. **Mockito is a declared Java agent** of the test JVM (`maven-dependency-plugin:properties` +
   Surefire `argLine`), with `-Xshare:off` to silence the class-sharing warning its boot class path
   append causes.
4. **Coverage**: JaCoCo on every `mvn verify`, report in `target/site/jacoco/`, build fails below
   **90 % lines and 80 % branches** of the whole bundle (91.7 % / 82.5 % measured before this ADR's
   tests, 93.3 % / 84.4 % after). A floor that
   catches untested code being added, not a target; raised as coverage rises, never lowered to pass.
5. **Mutation testing**: PIT in the `mutation` profile (`mvn clean -Pmutation verify`, ~3 minutes; after
   `clean`: without it the mutant set differed, 437 instead of 409, most likely from the IDE's compiler
   writing into `target/classes`), report in
   `target/pit-reports/`. Run before merging a change to guard code (filters, validation, parsers), and
   every survivor in the changed code is triaged: a missing test is added; an equivalent mutant or a
   false survivor (Spring caches) is accepted. **Not a build gate**: false survivors make the score
   unreliable as a threshold. The manual mutation check of each new guard test stays.
6. **No separate integration-test phase yet**: the suite takes ~20 s, so everything runs in Surefire's
   `mvn test`. With Testcontainers (Docker, slower), integration tests move to Failsafe (`*IT`,
   `mvn verify`).
7. **Contexts**: one per test class with its own probe configuration is accepted (15 now). Shared
   configuration (a common `@MockitoBean` set, a shared container) when module tests start to multiply.

## Consequences

**Positive**
- Module controllers can be tested in isolation (use cases mocked, no database once there is one), with
  the same Problem Details, validation codes and JSON rules as production.
- Test quality is measured: coverage on every build, mutation testing on demand. The triage found and
  closed real gaps (§ note 40).
- The build keeps working when the JDK stops allowing agents to attach themselves.

**Negative / accepted trade-offs**
- The `.imports` file is easy to overlook: a new application-wide MVC or Jackson setting outside the
  slice-scanned types must be added there. The slice guard tests catch the known ones, not future ones.
- PIT's result needs judgment: 34 of the 47 survivors left after the fixes are false (Spring caches).
  Arcmutate's commercial Spring plugin addresses this; not adopted. Measured from clean: 79 % → 84 %
  killed, test strength 84 % → 88 %.
- `-Xshare:off` makes the test JVM start slightly slower.
- The coverage floor is per bundle: a well-tested package can hide an untested one.

**Follow-ups**
- With PostgreSQL: Testcontainers, `@DataJpaTest` per repository, Failsafe for `*IT` classes, a
  shared container per test run.
- With Spring Security: a test that every endpoint without credentials is `401` unless allow-listed.
- With the OpenAPI document: responses validated against it (contract test).
- With CI: coverage report and PIT on pull requests that touch `shared/api`.

## References

- Spring Boot reference: Testing Spring Boot Applications (slices, `@MockitoBean`, `MockMvcTester`,
  `RestTestClient`), Testcontainers
- JaCoCo 0.8.15 (Java 25 support); PIT 1.30.1 with pitest-junit5-plugin 1.2.3 (runs JUnit 6; verified)
- Mockito documentation, "Explicitly setting up instrumentation for inline mocking (Java 21+)"
- Learning note: `learning/40-testing-rest-apis.md`
