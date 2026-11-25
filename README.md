# Spring Job Finder

A job-search backend built with **Java 25** and **Spring Boot 4**, developed step by step as a
learning project. Every concept used in the code, and many studied alongside it, is
documented and verified in the [`learning/`](learning/README.md) notes.

> **Status:** early stage. The Spring Boot application is set up with a production-style
> **health endpoint** (Actuator, liveness/readiness probes) and HTTP tests. The Job Finder REST
> API comes next; see the [roadmap](learning/roadmap.md).

## Tech stack

| | Current | Planned |
|-|---------|---------|
| Language | Java 25 | |
| Framework | Spring Boot 4.2 (Spring Framework 7.1), Spring MVC, Bean Validation, Spring Data Commons (paging types), Bucket4j (rate limiting), Micrometer Tracing + OpenTelemetry (trace IDs), Actuator | Spring Data JPA, Spring Security |
| Build | Maven | |
| Database | | PostgreSQL + Flyway |
| Testing | JUnit 6, AssertJ, Mockito, `@WebMvcTest` + `MockMvcTester`, `@JsonTest`, `@SpringBootTest` + `RestTestClient`, ArchUnit, Spring Modulith, JaCoCo (coverage floor), PIT (mutation testing) | Testcontainers |
| Ops | | Docker, GitHub Actions |

## Run it

Requirements: JDK 25 and Maven 3.9+.

```bash
mvn spring-boot:run          # dev profile: readable logs, DEBUG, health details,
                             # and automatic restart on recompile (Spring Boot DevTools)
```

Then check the health endpoint:

```bash
curl localhost:8080/actuator/health
# dev:  {"components":{"diskSpace":{...},"ping":{...},...},"groups":["liveness","readiness"],"status":"UP"}
# prod: {"groups":["liveness","readiness"],"status":"UP"}   (details hidden)
```

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Overall application health |
| `GET /actuator/health/liveness` | Is the app alive? (restart if not) |
| `GET /actuator/health/readiness` | Can it take traffic? |

API conventions:

- **Versioning:** URI path (`/api/v1/...`) using Spring Framework 7's built-in API versioning.
- **Errors:** RFC 9457 Problem Details (`application/problem+json`) for **every** error, including
  field-level validation errors and errors raised in filters. Every 5xx is logged once with its stack
  trace and never reveals internals; error text is always English. Error types are documented in
  [`docs/problems.md`](docs/problems.md) ([ADR 0006](docs/adr/0006-global-exception-handling.md),
  [ADR 0008](docs/adr/0008-exception-handling-hardening.md)).
- **DTOs:** endpoints accept and return records, never entities. Unknown request properties are
  rejected with `400` ([ADR 0005](docs/adr/0005-dtos-at-the-api-boundary.md)).
- **Validation:** Bean Validation on every request body (`@Valid` enforced by ArchUnit), strict JSON types
  (no `"5"` → 5 or `2.7` → 2), and a stable `code` per violation for bodies, parameters and headers
  ([ADR 0009](docs/adr/0009-request-validation.md)).
- **Pagination:** list endpoints take `?page=0&size=20&sort=createdAt,desc` (size ≤ 100, sortable properties
  declared per endpoint, `id` tiebreaker) and return `{"content": [...], "page": {...}}`; invalid paging input
  is a `400`, never silently replaced ([ADR 0010](docs/adr/0010-pagination-and-sorting.md)).
- **Filtering:** filters are validated query parameters (ISO-8601 dates, codes for list filters, normalized
  free text `q`); an **unknown parameter is a `400`**, so a misspelled filter never returns unfiltered results
  ([ADR 0011](docs/adr/0011-filtering-and-search.md)).
- **Rate limiting:** a token bucket per client on `/api/**` (60 requests, refilled over a minute); over the
  limit a `429` with `Retry-After`. Health probes are never limited ([ADR 0012](docs/adr/0012-rate-limiting.md)).
- **Request correlation:** every request has a W3C trace ID (a caller's valid `traceparent` is continued),
  printed in every log line (`trace.id` in prod JSON) and returned in the `X-Trace-Id` header and as `traceId`
  in every error, so a reported error leads straight to its log lines ([ADR 0013](docs/adr/0013-request-correlation.md)).
- **Idempotency keys:** `POST`/`PATCH` with an `Idempotency-Key` are safe to retry: the first successful
  response is replayed (`Idempotent-Replayed: true`) for 24 hours and the operation runs once, even for
  concurrent duplicates; the key with a different request is a `422`, during execution a `409`.
  `@Idempotent` endpoints require a key ([ADR 0014](docs/adr/0014-idempotency-keys.md)).
- **CORS:** browser origins are an explicit per-environment allowlist (`JOBFINDER_CORS_ALLOWEDORIGINS`),
  none by default, validated at startup ([ADR 0007](docs/adr/0007-cors-policy.md)).
- **Logging:** SLF4J. Readable in `dev`, structured ECS JSON in `prod` ([ADR 0004](docs/adr/0004-logging-per-environment.md)).

Build and run the executable jar (production style):

```bash
mvn clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar   # ECS JSON logs

# allow a browser frontend on another origin (none by default)
JOBFINDER_CORS_ALLOWEDORIGINS=https://app.example.com SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar
```

| Profile | Logs | Health details | Activated by |
|---------|------|----------------|--------------|
| `dev` | Compact colored lines, app at DEBUG | Shown | `mvn spring-boot:run` |
| `prod` | Structured **ECS JSON** to stdout | Hidden | `SPRING_PROFILES_ACTIVE=prod` |
| *(none)* | Spring Boot default pattern | Hidden | Default |

DevTools (fast restart) is development-only: it's excluded from the packaged jar, and a test
fails if it's ever active in a production-like launch.

## Testing

```bash
mvn test                     # the whole suite (~20 s)
mvn verify                   # + coverage report (target/site/jacoco/) and the coverage floor
mvn clean -Pmutation verify  # + mutation testing (target/pit-reports/, ~3 min)
```

Each layer is tested with the lowest kind of test that can see its behavior
([ADR 0015](docs/adr/0015-testing-strategy.md)): plain unit tests for rules and parsers,
`ApplicationContextRunner` for configuration, `@JsonTest` for the wire format, `@WebMvcTest` slices with
mocked use cases for controllers, and the real server over HTTP (`@SpringBootTest(RANDOM_PORT)`) for
filters, error handling and cross-cutting contracts. Slices are proven to run the same API contract as
the application. Coverage has a floor on every build (90 % lines, 80 % branches), and mutation testing
(PIT) checks that the code the tests run is also asserted on.

## Architecture

A **modular monolith** with **clean layers** inside each feature module
([ADR 0001](docs/adr/0001-modular-clean-architecture.md)):

```text
com.shahabuddin.jobfinder
├── job/                  ← feature module (top-level package = its public API)
│   ├── api/              REST controllers, request/response DTOs
│   ├── application/      use cases, transactions
│   ├── domain/           entities, business rules, repository interfaces
│   └── infrastructure/   adapters (persistence, external clients)
├── company/ …
└── shared/               cross-cutting concerns

api ──▶ application ──▶ domain ◀── infrastructure
```

The architecture is **enforced in the build**: `mvn test` fails if a rule is broken.

- **Spring Modulith:** no access to another module's internals, no cycles between modules.
- **ArchUnit:**
  - dependencies point inwards
  - the domain has no HTTP dependencies
  - controllers live only in `api` and never use repositories directly
  - endpoints accept and return DTOs, never domain types (generics included)
  - constructor injection only
  - no `System.out`
- **The rules are themselves tested** against deliberately violating fixtures, so a rule can't
  silently stop checking.

Key decisions are recorded as [Architecture Decision Records](docs/adr/README.md).

## Project structure

```text
src/main/java/com/shahabuddin/jobfinder/
├── JobFinderApplication.java      # @SpringBootApplication entry point
└── shared/                        # shared kernel module
    ├── DomainException.java       # sealed: ResourceNotFoundException (404), ConflictException (409)
    ├── PageQuery.java             # the API's paging contract (+ @SortableBy allowlist)
    ├── SearchText.java            # normalizes free-text search input
    ├── Idempotent.java            # marks POST/PATCH endpoints that require an Idempotency-Key
    └── api/                       # GlobalExceptionHandler, ProblemDetailsErrorController (/error), CorsConfig, JsonBindingConfig, PageQuery binding, unknown-parameter check, RateLimitFilter, TraceIdFilter, IdempotencyFilter
src/main/resources/
├── application.properties         # shared: Actuator, API versioning, strict JSON, ISO dates, CORS (none), rate limit, idempotency keys, tracing (W3C only)
├── application-dev.properties     # dev: readable logs with trace ID, DEBUG, health details, CORS for localhost frontends
└── application-prod.properties    # prod: ECS JSON logs (trace.id / span.id)
src/test/java/com/shahabuddin/jobfinder/
├── HealthEndpointTests.java       # starts the app, calls the endpoints over HTTP
├── ErrorResponseTests.java        # locks in the Problem Details error format
├── ApiVersioningTests.java        # locks in /api/v1 versioning (Actuator unaffected)
├── RequestBodyBindingTests.java   # records bind; unknown JSON properties are rejected
├── ProfileConfigurationTests.java # starts the real app per profile in a child JVM (without DevTools)
├── shared/api/GlobalExceptionHandlingTests.java  # every error path over HTTP, incl. filters and log capture
├── shared/api/CorsTests.java     # CORS with browser headers (preflight, errors, filter 401); CorsPropertiesTests
├── shared/api/ValidationTests.java # request validation: codes, strict JSON types, parameters, headers
├── shared/api/PaginationTests.java # paging contract: defaults, limits, sort allowlist, tiebreaker; PageQueryTests
├── shared/api/FilteringTests.java # filters: unknown parameters, ISO dates, search text, list values
├── shared/api/RateLimitTests.java # 429 + Retry-After, health not limited, forged X-Forwarded-For, CORS, metrics
├── shared/api/TracingTests.java  # trace ID in header, log lines, errors; traceparent in, only traceparent out; async
├── shared/api/IdempotencyTests.java # replay, 422/409/400/413, 16 concurrent duplicates run once, errors free the key
├── shared/api/WebMvcSliceTests.java # controller slice template (MockMvcTester + @MockitoBean); slices get the API contract
├── shared/api/JsonSliceTests.java # wire format: ISO-8601 dates, nulls written, no coercion, unknown properties rejected
└── architecture/                  # architecture rules (ArchUnit + Spring Modulith) and their tests
docs/adr/                          # Architecture Decision Records
docs/problems.md                   # catalogue of the API's Problem Details types
learning/                          # step-by-step notes (see below)
AGENTS.md                          # instructions for AI coding agents (conventions, commands, definition of done)
CLAUDE.md                          # Claude Code entry point: imports AGENTS.md
.claude/                           # Claude Code: shared permissions, skills (/feature-module, /adr, /learning-note), reviewer subagent
```

## Learning notes

The [`learning/`](learning/README.md) folder is a step-by-step journal (41 notes so far). Each
note explains a concept from scratch, shows the code, includes **real output from running
experiments**, and ends with common mistakes and interview questions.

Topics so far: project setup, Java essentials, the IoC container (`BeanFactory` vs
`ApplicationContext`), bean naming, `@Primary` / `@Qualifier`, component scanning and
stereotypes, the bean lifecycle, `@Import`, backend layering, dependency injection and
autowiring, circular dependencies, programmatic bean registration (Spring 7
`BeanRegistrar`), all six bean scopes, eager vs lazy initialization,
`@SpringBootApplication` with Actuator health checks, `@RestController` / REST API design, request mapping and binding, API versioning, clean architecture
enforced with Spring Modulith and ArchUnit, per-environment logging with structured JSON, DevTools, JDBC fundamentals, Spring Data vs Spring Data JPA, how Spring Data JPA relates to Hibernate, and the
important Spring Data JPA components (projections, N+1, Specifications, auditing, optimistic locking) and interfaces
(repository hierarchy, keyset scrolling), derived query methods, `@Query` (JPQL, native, bulk updates), Lombok (with its JPA traps), the DTO pattern, global exception handling (and its production edge cases), CORS, request validation, pagination and sorting, filtering and search, rate limiting (every algorithm, measured), request correlation IDs (MDC, W3C trace context), idempotency keys (safe retries for `POST`), and testing a REST API (every kind of test, coverage and mutation testing).

The original plain-Spring demo code from the early notes is preserved at the git tag
[`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).

## Roadmap

1. **Spring Core** ✅ (learned): configuration, DI, scopes, lifecycle
2. **Spring Boot** (in progress): application setup ✅, health endpoint ✅, profiles, logging
3. **Job Finder API**: REST endpoints for jobs, companies and applications, JPA +
   PostgreSQL, search and pagination, JWT security, OpenAPI docs, Docker, CI

Details: [learning/roadmap.md](learning/roadmap.md).
