---
name: feature-module
description: Scaffold a new Job Finder feature module (e.g. job, company) with api/application/domain/infrastructure layers that pass the ArchUnit and Spring Modulith rules. Use when adding a new business capability or the first endpoint of a new resource.
argument-hint: <module-name> [resource description]
---

# Scaffold a feature module

Module to create: `$ARGUMENTS`. A module is a direct sub-package of `com.shahabuddin.jobfinder`
([ADR 0001](../../../docs/adr/0001-modular-clean-architecture.md)). Name it as a singular lowercase noun
(`job`, `company`). Don't add a module the roadmap doesn't call for without asking.

## 1. Read before writing

- `AGENTS.md` (architecture and API contract), `ArchitectureRules.java`, ADRs 0001, 0005, 0006, 0009–0011.
- The compliant fixture `src/test/java/archfixture/compliant/job/`: a minimal module that passes every rule.
- `shared/` public API: `DomainException` (sealed: `ResourceNotFoundException`, `ConflictException`),
  `PageQuery`, `@SortableBy`, `SearchText`.

## 2. Layout

```text
src/main/java/com/shahabuddin/jobfinder/<module>/
├── package-info.java        Javadoc: what the module owns, what its top-level package exposes
├── <PublicApi>.java         only if another module needs it (the top-level package IS the public API)
├── api/                     <Resource>Controller + request/response records
├── application/             <Resource>Service (@Service): use cases, transactions
├── domain/                  entity / value objects, business rules, <Resource>Repository interface
└── infrastructure/          adapter implementing <Resource>Repository
```

Create only the layers the change needs; empty layers are allowed by the rules.

## 3. Rules the code must follow (the build enforces most of them)

- Controller: class-level `@RequestMapping("/api/{version}/<resources>")`, `version = "1"` on every
  mapping method. Constructor injection of the application service only, never a repository.
- Endpoints take and return **records**, never domain types (including inside `List<>`, `ResponseEntity<>`).
  Map in a private static helper in the controller.
- Request records: `@Valid @RequestBody`; constraints on every field; wrapper types + `@NotNull`, never
  primitives; `@Valid` on nested objects and collections. Response records may use primitives.
- Lists: `@SortableBy(value = {...}, defaultSort = "createdAt,desc") PageQuery page` in, `PagedModel<...>`
  out. Filters are a validated record bound from query parameters; free text goes through `SearchText`.
- Not found / conflict: throw `ResourceNotFoundException` / `ConflictException` from the application
  layer. Never add a `@ControllerAdvice`; never catch-and-return error bodies in controllers.
- Domain imports nothing from `org.springframework.web`, `org.springframework.http`, `jakarta.servlet`.
- Logging via SLF4J; config via a `@ConfigurationProperties` record under `jobfinder.<module>.*`.

## 4. Tests (same change)

- `<Resource>ApiTests` in `src/test/java/com/shahabuddin/jobfinder/<module>/api/`:
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient`, covering the happy
  path, validation errors (assert `type`, `errors[].pointer`, `errors[].code`), 404 Problem Details,
  paging/sorting allowlist and unknown query parameters.
- Unit tests for business rules in `domain`.

## 5. Verify and document

1. `mvn -q test`: all green, including `ArchitectureTests` and `ModularityTests`.
2. Start it (`mvn spring-boot:run`) and hit the endpoints with `curl`, including one invalid request.
3. Update `README.md` (structure, endpoints), `docs/problems.md` if a new problem type was added, an ADR
   for any significant decision (`/adr`), and a learning note (`/learning-note`).
4. Report the commands run and their results.
