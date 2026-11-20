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
| Framework | Spring Boot 4.2 (Spring Framework 7.1), Spring MVC, Actuator | Spring Data JPA, Spring Security |
| Build | Maven | |
| Database | | PostgreSQL + Flyway |
| Testing | JUnit 6, `@SpringBootTest` + `RestTestClient`, ArchUnit, Spring Modulith | Testcontainers |
| Ops | | Docker, GitHub Actions |

## Run it

Requirements: JDK 25 and Maven 3.9+.

```bash
mvn spring-boot:run          # starts with the dev profile: readable logs, DEBUG, health details
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
- **Errors:** RFC 9457 Problem Details (`application/problem+json`).
- **Logging:** SLF4J. Readable in `dev`, structured ECS JSON in `prod` ([ADR 0004](docs/adr/0004-logging-per-environment.md)).

Build and run the executable jar (production style):

```bash
mvn clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar   # ECS JSON logs
```

| Profile | Logs | Health details | Activated by |
|---------|------|----------------|--------------|
| `dev` | Compact colored lines, app at DEBUG | Shown | `mvn spring-boot:run` |
| `prod` | Structured **ECS JSON** to stdout | Hidden | `SPRING_PROFILES_ACTIVE=prod` |
| *(none)* | Spring Boot default pattern | Hidden | Default |

Run the tests: `mvn test`

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
  - constructor injection only
  - no `System.out`
- **The rules are themselves tested** against deliberately violating fixtures, so a rule can't
  silently stop checking.

Key decisions are recorded as [Architecture Decision Records](docs/adr/README.md).

## Project structure

```text
src/main/java/com/shahabuddin/jobfinder/
└── JobFinderApplication.java      # @SpringBootApplication entry point
src/main/resources/
├── application.properties         # shared: Actuator, Problem Details, API versioning
├── application-dev.properties     # dev: readable logs, DEBUG, health details
└── application-prod.properties    # prod: ECS JSON logs
src/test/java/com/shahabuddin/jobfinder/
├── HealthEndpointTests.java       # starts the app, calls the endpoints over HTTP
├── ErrorResponseTests.java        # locks in the Problem Details error format
├── ApiVersioningTests.java        # locks in /api/v1 versioning (Actuator unaffected)
├── ProfileConfigurationTests.java # starts the real app per profile in a child JVM
└── architecture/                  # architecture rules (ArchUnit + Spring Modulith) and their tests
docs/adr/                          # Architecture Decision Records
learning/                          # step-by-step notes (see below)
```

## Learning notes

The [`learning/`](learning/README.md) folder is a step-by-step journal (21 notes so far). Each
note explains a concept from scratch, shows the code, includes **real output from running
experiments**, and ends with common mistakes and interview questions.

Topics so far: project setup, Java essentials, the IoC container (`BeanFactory` vs
`ApplicationContext`), bean naming, `@Primary` / `@Qualifier`, component scanning and
stereotypes, the bean lifecycle, `@Import`, backend layering, dependency injection and
autowiring, circular dependencies, programmatic bean registration (Spring 7
`BeanRegistrar`), all six bean scopes, eager vs lazy initialization,
`@SpringBootApplication` with Actuator health checks, `@RestController` / REST API design, request mapping and binding, API versioning, clean architecture
enforced with Spring Modulith and ArchUnit, and per-environment logging with structured JSON.

The original plain-Spring demo code from the early notes is preserved at the git tag
[`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).

## Roadmap

1. **Spring Core** ✅ (learned): configuration, DI, scopes, lifecycle
2. **Spring Boot** (in progress): application setup ✅, health endpoint ✅, profiles, logging
3. **Job Finder API**: REST endpoints for jobs, companies and applications, JPA +
   PostgreSQL, search and pagination, JWT security, OpenAPI docs, Docker, CI

Details: [learning/roadmap.md](learning/roadmap.md).
