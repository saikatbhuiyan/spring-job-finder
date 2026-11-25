# ADR 0001: Modular monolith with clean layers inside each module

- **Status:** Accepted; extended by [ADR 0015](0015-testing-strategy.md) (how each layer is tested)
- **Date:** 2026-11-20

## Context

Job Finder will grow feature by feature (jobs, companies, job applications, users, search,
notifications). It's built as a production-grade, portfolio-quality backend, so the code structure
must:

- keep each feature easy to find, change and test on its own;
- keep business rules independent of HTTP, persistence and other frameworks;
- stay understandable as the code base grows;
- allow a module to be extracted into its own service later, if ever needed;
- **be enforced automatically**, not just documented.

Options considered:

1. **Classic layered (package by layer):** `controller/`, `service/`, `repository/`, `entity/`.
   Simple, but a feature is scattered across the whole tree, everything is `public`, and nothing
   stops cross-feature coupling.
2. **Full hexagonal / ports & adapters:** a pure-Java domain (no framework annotations), separate JPA
   entities with mappers, and explicit in/out port interfaces for every use case. The strongest
   isolation, but roughly double the classes per feature, which is costly for a team of one.
3. **Modular monolith with clean layers (chosen):** feature modules, each with
   `api → application → domain ← infrastructure`.

## Decision

1. **Feature modules.** Every direct sub-package of `com.shahabuddin.jobfinder` is a module
   (`job`, `company`, `application`, …), plus `shared` for cross-cutting code. A module's top-level
   package is its **public API**. Sub-packages are internal to the module.
2. **Clean layers inside a module:**

   | Layer | Contains | May depend on |
   |-------|----------|---------------|
   | `api` | `@RestController`s, request/response DTOs (records), DTO mapping | application, domain |
   | `application` | Use-case services (`@Service`, transactions), orchestration | domain |
   | `domain` | Entities, value objects, business rules, repository interfaces | nothing above it |
   | `infrastructure` | Adapters: persistence implementations, external clients, messaging | domain, application |

   Dependencies point **inwards** to the domain.
3. **Pragmatic domain:** JPA mapping annotations and Spring Data repository interfaces are allowed
   in `domain` (avoiding a parallel entity model plus mappers). The domain must **not** depend on web
   or HTTP types (`org.springframework.web`, `org.springframework.http`, `jakarta.servlet`).
4. **Enforcement in the build** (`mvn test` fails on violations):
   - **Spring Modulith** (`ModularityTests`): module boundaries (no access to another module's
     internal packages) and no cycles between modules. Also generates module diagrams.
   - **ArchUnit** (`ArchitectureTests`): layer dependencies, a web-agnostic domain, no repository
     access from `api` (controllers go through `application`), controllers only in `api`, no field
     injection, no `System.out`, no `java.util.logging`.
   - Both rule sets are themselves tested against deliberately violating fixtures
     (`ArchitectureRulesTests`, `ModularityRulesTests`), so a rule can't silently stop working.

## Consequences

**Positive**
- A feature's code lives in one place, and its internals are hidden from other modules.
- Business logic is testable without HTTP. Controllers stay thin.
- Architecture erosion is caught in CI with precise messages, not in code review months later.
- A module could be extracted into a separate service along an existing, verified boundary.

**Negative / accepted trade-offs**
- More packages than a flat layered layout. A small feature still gets its own module.
- JPA annotations in the domain couple it to the persistence model. Accepted to avoid duplicate
  entity and mapper classes. Revisit (move to option 2 for a module) if a module's domain becomes
  complex enough to justify it.
- Rules use `allowEmptyShould(true)` while layers are still empty. The fixture tests make sure the
  rules are real.

## References

- Learning note: `learning/19-clean-architecture.md`
- Spring Modulith reference documentation: "Application Modules" and "Verifying Application Module Structure"
- ArchUnit user guide: "Layer Checks" and "General Coding Rules"
