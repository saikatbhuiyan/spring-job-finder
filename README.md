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
| Testing | JUnit 6, `@SpringBootTest` + `RestTestClient` | Testcontainers |
| Ops | | Docker, GitHub Actions |

## Run it

Requirements: JDK 25 and Maven 3.9+.

```bash
mvn spring-boot:run
```

Then check the health endpoint:

```bash
curl localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Overall application health |
| `GET /actuator/health/liveness` | Is the app alive? (restart if not) |
| `GET /actuator/health/readiness` | Can it take traffic? |

API conventions:
- **Versioning:** URI path (`/api/v1/...`) using Spring Framework 7's built-in API versioning.
- **Errors:** RFC 9457 Problem Details (`application/problem+json`).

Build and run the executable jar:

```bash
mvn clean package
java -jar target/job-finder-0.0.1-SNAPSHOT.jar
```

Run the tests: `mvn test`

## Project structure

```text
src/main/java/com/shahabuddin/jobfinder/
└── JobFinderApplication.java      # @SpringBootApplication entry point
src/main/resources/
└── application.properties         # Actuator, Problem Details, API versioning
src/test/java/com/shahabuddin/jobfinder/
├── HealthEndpointTests.java       # starts the app, calls the endpoints over HTTP
├── ErrorResponseTests.java        # locks in the Problem Details error format
└── ApiVersioningTests.java        # locks in /api/v1 versioning (Actuator unaffected)
learning/                          # step-by-step notes (see below)
```

## Learning notes

The [`learning/`](learning/README.md) folder is a step-by-step journal (19 notes so far). Each
note explains a concept from scratch, shows the code, includes **real output from running
experiments**, and ends with common mistakes and interview questions.

Topics so far: project setup, Java essentials, the IoC container (`BeanFactory` vs
`ApplicationContext`), bean naming, `@Primary` / `@Qualifier`, component scanning and
stereotypes, the bean lifecycle, `@Import`, backend layering, dependency injection and
autowiring, circular dependencies, programmatic bean registration (Spring 7
`BeanRegistrar`), all six bean scopes, eager vs lazy initialization,
`@SpringBootApplication` with Actuator health checks, `@RestController` / REST API design, request mapping and binding, and API versioning.

The original plain-Spring demo code from the early notes is preserved at the git tag
[`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).

## Roadmap

1. **Spring Core** ✅ (learned): configuration, DI, scopes, lifecycle
2. **Spring Boot** (in progress): application setup ✅, health endpoint ✅, profiles, logging
3. **Job Finder API**: REST endpoints for jobs, companies and applications, JPA +
   PostgreSQL, search and pagination, JWT security, OpenAPI docs, Docker, CI

Details: [learning/roadmap.md](learning/roadmap.md).
