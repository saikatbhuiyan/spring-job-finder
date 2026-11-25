# AGENTS.md

Instructions for AI coding agents (Claude Code, Codex, Cursor, Copilot, Gemini, …) working in this
repository. Humans: start with [README.md](README.md).

## What this is

**Spring Job Finder**: a job-search REST backend, built step by step as a learning project and kept to a
**production-grade, portfolio standard**. It is reviewed by hiring engineers, so every change, however
small, must be what a senior engineer would approve for production.

- Java **25**, Spring Boot **4.2** (Spring Framework 7.1), Spring MVC, Bean Validation, Actuator
- Jackson **3** (package `tools.jackson`, not `com.fasterxml.jackson`), JUnit **6**
- Spring Modulith 2.2 + ArchUnit 1.5.1 (architecture enforced in tests), Bucket4j + Caffeine (rate limiting),
  Micrometer Tracing + OpenTelemetry bridge (trace IDs; no exporter yet)
- Maven 3.9+ (no wrapper; use `mvn`). Planned: Spring Data JPA, PostgreSQL + Flyway, Spring Security,
  Testcontainers, Docker, CI ([roadmap](learning/roadmap.md)).

Base package: `com.shahabuddin.jobfinder`. Remote: `github.com/saikatbhuiyan/spring-job-finder`, branch `main`.

## Commands

```bash
mvn -q test                                   # full suite: ~220 tests, ~20 s. Must pass before any change is done
mvn -q test -Dtest=RateLimitTests             # one test class
mvn -q test -Dtest='ArchitectureTests,ModularityTests'   # architecture rules only
mvn spring-boot:run                           # dev profile, http://localhost:8080
curl -s localhost:8080/actuator/health
mvn clean package && SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar
```

Stack traces in test output are **expected**: tests deliberately trigger 500s to check they're logged
once and never leak details. Judge a run by Maven's exit code and the `Tests run:` summary, not by the
presence of stack traces.

## Architecture (enforced: `mvn test` fails on a violation)

Modular monolith with clean layers inside each module ([ADR 0001](docs/adr/0001-modular-clean-architecture.md)):

```text
com.shahabuddin.jobfinder
├── <module>/            job, company, …: the top-level package IS the module's public API
│   ├── api/             @RestControllers, request/response DTOs (records), DTO mapping
│   ├── application/     use cases (@Service), transactions
│   ├── domain/          entities, value objects, business rules, repository interfaces
│   └── infrastructure/  adapters: persistence, external clients
└── shared/              shared kernel: DomainException, PageQuery, SearchText; shared/api = HTTP plumbing

api ──▶ application ──▶ domain ◀── infrastructure
```

Rules live in `src/test/java/com/shahabuddin/jobfinder/architecture/ArchitectureRules.java` (ArchUnit)
and `ModularityTests` (Spring Modulith). In short:

- Dependencies point inwards; `domain` never imports `org.springframework.web..`, `..http..` or `jakarta.servlet..`.
- No module touches another module's sub-packages; no cycles between modules.
- Controllers only in `api`, never inject a `*Repository`; they call the application layer.
- Endpoints accept and return **DTO records, never domain types** (generic arguments included).
- Every `@RequestBody` has `@Valid`; nested objects/collections have `@Valid` on the property; **no
  primitive fields** in request DTOs (use wrappers + `@NotNull`).
- Endpoints page with `PageQuery` (+ `@SortableBy`) and return `PagedModel`, never `Pageable`/`Sort`/`Page`/`Slice`.
- Exactly one `@ControllerAdvice`: `shared/api/GlobalExceptionHandler`. Modules throw `DomainException`
  subtypes (`ResourceNotFoundException` → 404, `ConflictException` → 409); never add a module advice.
- Constructor injection only; SLF4J only (no `System.out`, no `java.util.logging`).

**Never weaken a rule to make code pass.** If a rule is genuinely wrong, that's a design decision: a new
ADR plus a change to the rule. Every rule is itself tested against deliberately violating fixtures
(`src/test/java/archfixture/`, `modfixture/`): a new or changed rule needs a fixture proving it fails
on bad code (`ArchitectureRulesTests`, `ModularityRulesTests`).

## API contract (details in the ADRs; don't re-decide them)

| Concern | Convention | ADR |
| --- | --- | --- |
| Versioning | URI path `/api/v1/...`: class-level `@RequestMapping("/api/{version}/jobs")`, and `version = "1"` on every mapping method (`@GetMapping(path = "/{id}", version = "1")`) | [0002](docs/adr/0002-api-versioning-via-uri-path.md) |
| Errors | RFC 9457 Problem Details for **every** error, incl. filters; 5xx logged once, never leak internals; English only | [0003](docs/adr/0003-problem-details-for-errors.md), [0006](docs/adr/0006-global-exception-handling.md), [0008](docs/adr/0008-exception-handling-hardening.md) |
| New error type | Add to `ProblemTypes` **and** document it in [docs/problems.md](docs/problems.md); type URIs never change | 0006 |
| DTOs | Records; unknown JSON properties → 400 | [0005](docs/adr/0005-dtos-at-the-api-boundary.md) |
| Validation | Strict JSON types (no coercion), stable violation `code`s, rejected values never echoed | [0009](docs/adr/0009-request-validation.md) |
| Paging | `?page=0&size=20&sort=createdAt,desc`, size ≤ 100, sort allowlist, `id` tiebreaker; bad input = 400 | [0010](docs/adr/0010-pagination-and-sorting.md) |
| Filtering | Filter records, ISO-8601 dates, normalized `q` (`SearchText`); unknown query parameter = 400 | [0011](docs/adr/0011-filtering-and-search.md) |
| Rate limiting | Token bucket per `getRemoteAddr()` on `/api/**`; 429 + `Retry-After`; never read `X-Forwarded-For` in code | [0012](docs/adr/0012-rate-limiting.md) |
| Correlation | The W3C trace ID is the correlation ID: `X-Trace-Id` header + `traceId` in every problem; never a hand-rolled request ID or MDC key; W3C propagation only, no baggage; use Boot's executors and `RestClient.Builder` so the trace propagates | [0013](docs/adr/0013-request-correlation.md) |
| Idempotency | Create-like `POST`/`PATCH` endpoints get `@Idempotent` (key required); any keyed `POST`/`PATCH` is replayed, never re-executed; only 2xx stored; new error types documented in docs/problems.md | [0014](docs/adr/0014-idempotency-keys.md) |
| CORS | Per-environment allowlist (`jobfinder.cors.allowed-origins`), none by default | [0007](docs/adr/0007-cors-policy.md) |
| Logging | Readable in `dev`, ECS JSON in `prod` | [0004](docs/adr/0004-logging-per-environment.md) |

Read the relevant ADR before touching its area. Behavior they describe is locked in by tests.

## Code conventions

- Match the existing code before inventing a pattern. Look at `shared/` first.
- Configuration: `@ConfigurationProperties` **records** under the `jobfinder.*` prefix, validated at
  startup (fail fast on bad config). Every property in `application*.properties` gets a comment
  explaining *why*, with the ADR it implements.
- Comments and Javadoc explain the *why* and the trap avoided (often "verified: …"), not the *what*.
- Secure defaults: Actuator exposes `health` only, health details hidden outside `dev`, no secrets in
  code or properties (use env vars, e.g. `JOBFINDER_CORS_ALLOWEDORIGINS`).
- Dependencies: versions come from the Spring Boot BOM where possible. A dependency outside it gets a
  version property and a comment saying why it's there; don't adopt a release younger than a few weeks.
  Every dependency in `pom.xml` has a comment explaining its purpose.

## Testing conventions

- Every behavior gets a test; HTTP behavior is tested over real HTTP:
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient` + `RestTestClient`.
- Tests that lock in an ADR say so in their Javadoc. For guard tests, do a **mutation check**: break the
  guarded code, confirm the test fails, restore it.
- Wait for async outcomes with Awaitility, never `Thread.sleep`.
- Test-only property overrides go in `src/test/resources/config/application.properties` (layered on top
  of the main file). The rate limit is raised there; `RateLimitTests` sets its own small limit.
- `ProfileConfigurationTests` starts the real app in a child JVM per profile; it is the slowest class.

## Documentation is part of the change

A change is not done until its docs are updated in the same change:

1. **ADR** ([docs/adr/](docs/adr/README.md)) for any significant decision: next number, Context /
   Decision / trade-offs, add it to the index table. Accepted ADRs are never rewritten; a changed
   decision gets a new ADR that supersedes or extends the old one (update the old one's status only).
2. **Problem types** in [docs/problems.md](docs/problems.md) when the API gains an error type.
3. **Learning note** in [learning/](learning/README.md): the owner is new to Java/Spring and learns from
   these. Every concept applied (or studied) gets a beginner-friendly note `NN-topic.md` (next number)
   with the sections Goal, Concepts, In this project, Run it / verify it (real output), Common mistakes,
   Interview questions; plus a row in the index table and the dated timeline in `learning/README.md`.
4. **README.md** when status, stack, conventions, structure or test counts change (it's a portfolio page).

## Definition of done

- Root cause found and fixed (no loosened assertions, special cases or suppressed rules).
- `mvn -q test` passes, and the change was **run**, not just compiled: tests for new behavior, plus a
  real request against `mvn spring-boot:run` for anything user-facing.
- Docs above updated. Report exactly which commands were run and what was checked.
- Bugs found along the way are reported, not silently fixed inside unrelated work.

## Git

- Commit or push only when asked. Never force-push `main`.
- Commit subjects: short, imperative, sentence case, no prefix or trailing period. Code and its learning
  note are separate commits, e.g. `Add rate limiting contract` then `Add rate limiting note`.
- No AI attribution in commits: no `Co-Authored-By` for an AI, no "Generated with …" lines.
- Never delete or move the tag `spring-core-basics` (learning notes 00–04 link to it).
- `target/`, `.idea/`, `.vscode/` are ignored; never commit them, or `.env` files or credentials.
