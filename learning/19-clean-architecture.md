# 19 — Clean Architecture: Structure, Rules, and Enforcement

## Goal

> Following clean architecture practices makes a project **scalable, readable, and easy to
> maintain**.

That's true only if the architecture is **actually followed**, on day 1 and on day 500. This
note covers:

1. what "clean architecture" means (the dependency rule);
2. the three common ways to structure a Spring app, and which one Job Finder uses;
3. how the structure is **enforced by tests** (Spring Modulith + ArchUnit);
4. how we proved the enforcement itself works.

It builds on [note 05](05-backend-layers.md) (layers) and [note 07](07-stereotype-annotations.md)
(stereotypes). The decision is recorded in
[ADR 0001](../docs/adr/0001-modular-clean-architecture.md).

Status: ✅ **applied**. Structure decided, rules enforced in the build, 28 tests passing.

---

## 1. The core idea: the dependency rule

Clean architecture (Robert C. Martin), hexagonal architecture (Alistair Cockburn) and onion
architecture all share one idea:

```text
        ┌─────────────────────────────────────────┐
        │  Frameworks & delivery: HTTP, DB, UI     │   ← details, change often
        │   ┌─────────────────────────────────┐    │
        │   │  Application: use cases          │    │
        │   │   ┌─────────────────────────┐    │    │
        │   │   │  DOMAIN: business rules  │    │    │   ← the core, changes least
        │   │   └─────────────────────────┘    │    │
        │   └─────────────────────────────────┘    │
        └─────────────────────────────────────────┘
             Dependencies point INWARDS only.
```

**The dependency rule:** code in an inner circle must never know about code in an outer circle.
"A job posting must have a title" doesn't care whether the request came over HTTP, a message
queue, or a test, or whether jobs are stored in PostgreSQL or in memory.

What that buys you:

| Benefit | Why |
|---------|-----|
| **Testable** | Business rules can be tested without starting a web server or database |
| **Replaceable details** | Swap the database, add a message queue, or add GraphQL next to REST without touching the core |
| **Readable** | The domain package *describes the business*, not the framework |
| **Scalable (team and code)** | Clear boundaries mean fewer conflicts and smaller blast radius per change |

---

## 2. Three ways to structure a Spring application

### A. Package by layer (classic)

```text
controller/   service/   repository/   entity/   dto/
```

Every feature is spread over five folders, every class is `public`, and nothing prevents
`JobService` from calling `CompanyRepository` directly. Fine for tutorials, but it degrades as
the app grows.

### B. Full hexagonal (ports & adapters)

```text
job/domain/            pure Java, no annotations
job/application/port/in/   PostJobUseCase (interface)
job/application/port/out/  LoadJobPort, SaveJobPort (interfaces)
job/adapter/in/web/        controller → in-port
job/adapter/out/persistence/  JobJpaEntity + mapper → out-port
```

The strongest isolation, but about twice the classes per feature (a separate JPA entity and mapper
for every domain object, an interface for every use case).

### C. ✅ Modular monolith + clean layers (Job Finder)

```text
com.shahabuddin.jobfinder
├── JobFinderApplication
├── job/                      ← module (its top-level package = its public API)
│   ├── api/                  @RestController, request/response DTOs (records)
│   ├── application/          use-case services (@Service, @Transactional)
│   ├── domain/               entities, value objects, rules, repository interfaces
│   └── infrastructure/       adapters: persistence impls, external clients
├── company/
├── application/              (job applications)
└── shared/                   cross-cutting: error handling, config
```

```text
api ──▶ application ──▶ domain ◀── infrastructure
```

**Why C:** it gives most of B's benefits (independent core, enforced boundaries) at a fraction of
the code, and it's how many production Spring teams work today. It's a **pragmatic** choice, with
one deliberate trade-off: JPA annotations are allowed on domain entities (see ADR 0001).

### What each layer is for

| Layer | Put here | Never put here |
|-------|----------|----------------|
| `api` | Controllers, request/response DTOs, mapping entity → DTO | Business rules, SQL |
| `application` | "Post a job", "apply to a job": orchestration, transactions, calling repositories | HTTP types, DTOs from `api` |
| `domain` | `Job`, `Salary`, "a closed job can't receive applications", `JobRepository` interface | `ResponseEntity`, `HttpServletRequest`, controllers |
| `infrastructure` | Repository implementations, email/SMS clients, job-board API clients | Controllers |

### Modules vs layers

- A **module** is a vertical slice: one business capability (jobs, companies).
- **Layers** are horizontal slices inside a module.
- Modules talk to each other only through each other's **public API** (the top-level package),
  or via events (note 10 §5), never through another module's internals.

---

## 3. Architecture as code: enforcing the rules

Writing the rules down isn't enough. Under deadline pressure someone will call a repository from
a controller "just this once". So the rules are **tests**, and `mvn test` fails on any violation.

### 3.1 Spring Modulith: module boundaries

[ModularityTests.java](../src/test/java/com/shahabuddin/jobfinder/architecture/ModularityTests.java):

```java
private final ApplicationModules modules = ApplicationModules.of(JobFinderApplication.class);

@Test void modulesRespectBoundaries() { modules.verify(); }

@Test void writeModuleDocumentation() { new Documenter(modules).writeDocumentation(); }
```

Spring Modulith (from the Spring team, version 2.2.0 here) treats every direct sub-package of
the application's package as a **module** and checks that:

- no module uses another module's **internal** sub-packages;
- there are **no cycles** between modules.

`Documenter` generates PlantUML component diagrams into `target/spring-modulith-docs`
(`components.puml`, `all-docs.adoc`). These can be published in docs later.

### 3.2 ArchUnit: layers and coding rules

[ArchitectureRules.java](../src/test/java/com/shahabuddin/jobfinder/architecture/ArchitectureRules.java)
defines the rules once, and
[ArchitectureTests.java](../src/test/java/com/shahabuddin/jobfinder/architecture/ArchitectureTests.java)
applies them to the production classes:

```java
public static final ArchRule LAYER_DEPENDENCIES = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("Api").definedBy("..api..")
        .layer("Application").definedBy("..application..")
        .layer("Domain").definedBy("..domain..")
        .layer("Infrastructure").definedBy("..infrastructure..")
        .whereLayer("Api").mayNotBeAccessedByAnyLayer()
        .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Infrastructure")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
        .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
        ...
```

| Rule | Enforces |
|------|----------|
| `LAYER_DEPENDENCIES` | `api → application → domain ← infrastructure` |
| `DOMAIN_IS_WEB_AGNOSTIC` | The domain never imports `org.springframework.web`, `org.springframework.http` or `jakarta.servlet` |
| `API_DOES_NOT_USE_REPOSITORIES` | Controllers never inject a `*Repository`; they call the application layer |
| `CONTROLLERS_LIVE_IN_API` | `@RestController` / `@Controller` only in `api` |
| `NO_FIELD_INJECTION` | Constructor injection only (note 08) |
| `NO_STANDARD_STREAMS` | No `System.out` / `System.err`; use the logger |
| `NO_JAVA_UTIL_LOGGING` | One logging API (SLF4J via Boot) |

The package patterns (`..domain..`) apply to **every** module automatically. New modules are
covered without changing the rules.

### 3.3 Why the two tools together

| | Spring Modulith | ArchUnit |
|-|-----------------|----------|
| Checks | **Between** modules: boundaries, cycles | **Inside** modules: layers, coding rules |
| Knows Spring | ✅ Modules, events, documentation | General Java analysis |
| Modulith uses ArchUnit internally | Same version, 1.5.1 (verified in its POM) | |

---

## 4. Testing the tests (verified)

Production has almost no classes yet, so the rules pass trivially, and a broken rule would pass
too. To prove each rule is real, it's checked against **fixtures**: small sample classes that
violate it on purpose, kept in test sources **outside** `com.shahabuddin.jobfinder` so production
checks never see them.

### ArchUnit rules vs `archfixture`

| Fixture class (in `archfixture.violations.job`) | Violation | Caught by |
|-------------------------------------------------|-----------|-----------|
| `domain.Job` uses `api.JobResponse` | Domain → api | `LAYER_DEPENDENCIES` ✅ |
| `domain.WebAwareJob` returns `ResponseEntity` | Domain knows HTTP | `DOMAIN_IS_WEB_AGNOSTIC` ✅ |
| `api.ShortcutController` injects `domain.JobRepository` | Controller bypasses the application layer | `API_DOES_NOT_USE_REPOSITORIES` ✅ |
| `application.MisplacedController` (`@RestController`) | Controller outside api | `CONTROLLERS_LIVE_IN_API` ✅ |
| `application.FieldInjectedService` (`@Autowired` field) | Field injection | `NO_FIELD_INJECTION` ✅ |
| `application.ConsolePrinter` (`System.out`) | Standard streams | `NO_STANDARD_STREAMS` ✅ |
| `application.JulLogging` | java.util.logging | `NO_JAVA_UTIL_LOGGING` ✅ |

And `archfixture.compliant.job` (a correct module: controller → service → repository interface ←
in-memory implementation) passes **every** rule, so there are no false positives.

Real failure message (verified):

```text
Architecture Violation [Priority: MEDIUM] - Rule 'classes that are annotated with @RestController
or are annotated with @Controller should reside in a package '..api..', because controllers are
the HTTP adapter of a module' was violated (1 times):
Class <archfixture.violations.job.application.MisplacedController> does not reside in a package '..api..'
```

### Spring Modulith vs `modfixture`

A fixture app where module `job` reaches into `company.internal` and the two modules depend on
each other:

```text
- Cycle detected: Slice company -> Slice job -> Slice company
- Module 'job' depends on non-exposed type modfixture.company.internal.CompanyRatingCalculator
  within module 'company'!
```

Both are asserted in
[ModularityRulesTests.java](../src/test/java/com/shahabuddin/jobfinder/architecture/ModularityRulesTests.java).

### Two bugs found while writing these tests (root-caused)

1. **`No classes found in packages [modfixture]!`:** `ApplicationModules.of(Class)` ignores
   **test** classes by default, which is correct for production checks. The fixture lives in test
   sources, so the test uses `ApplicationModules.of(ModFixtureApplication.class, location -> true)`.
2. **The second assertion silently passed with no exception.** Each test alone was fine, but
   together the second failed with "Expecting code to raise a throwable". Inspecting the class
   (`javap`) showed a static `CACHE` of `ApplicationModules` and a private `verified` flag:
   **`verify()` only runs once per cached instance.** The fix was `detectViolations()`, which
   re-evaluates and returns the violations instead of throwing. That's exactly why rules need
   their own tests: a check that silently does nothing is worse than no check.

### Full test suite

```text
HealthEndpointTests       5   (note 15)
ErrorResponseTests        1   (note 16)
ApiVersioningTests        3   (note 18)
ArchitectureTests         7   rules on production code
ArchitectureRulesTests    8   7 violations rejected + compliant module accepted
ModularityTests           2   module verification + documentation
ModularityRulesTests      2   internal access + cycle rejected
──────────────────────────
Total                    28   Failures: 0
```

---

## 5. Architecture Decision Records (ADRs)

Production teams record **why** a decision was made, not only what. An ADR is a short Markdown
file: context → decision → consequences (including the downsides accepted). It's never rewritten.
A changed decision gets a new ADR that supersedes the old one.

[docs/adr/](../docs/adr/README.md):

| ADR | Decision |
|-----|----------|
| [0001](../docs/adr/0001-modular-clean-architecture.md) | Modular monolith with clean layers (this note) |
| [0002](../docs/adr/0002-api-versioning-via-uri-path.md) | API versioning via `/api/v1` (note 18) |
| [0003](../docs/adr/0003-problem-details-for-errors.md) | RFC 9457 Problem Details (note 16) |

In interviews, being able to say *"here's the ADR, the alternatives we rejected, and the trade-off
we accepted"* shows senior-level thinking.

---

## 6. How the first feature will look

When the `job` module is built (🔜), it will look like:

```java
// job/domain/Job.java (business rules live here)
@Entity
public class Job {
    @Id @GeneratedValue private Long id;
    private String title;
    private JobStatus status;

    public void close() {
        if (status == JobStatus.CLOSED) throw new JobAlreadyClosedException(id);
        status = JobStatus.CLOSED;
    }
}

// job/domain/JobRepository.java
public interface JobRepository extends JpaRepository<Job, Long> { }

// job/application/JobService.java
@Service
class JobService {
    private final JobRepository jobs;
    JobService(JobRepository jobs) { this.jobs = jobs; }

    @Transactional
    void closeJob(long id) { jobs.findById(id).orElseThrow(...).close(); }
}

// job/api/JobController.java
@RestController
@RequestMapping("/api/{version}/jobs")
class JobController {
    private final JobService service;
    ...
    @PostMapping(path = "/{id}/close", version = "1+")
    ResponseEntity<Void> close(@PathVariable long id) { service.closeJob(id); return ResponseEntity.noContent().build(); }
}
```

Notice that the business rule ("can't close twice") is **in the domain**, the transaction is in
**application**, and HTTP is only in **api**. The tests guarantee it stays that way.

---

## Common mistakes

| Mistake | Consequence | Caught by |
|---------|-------------|-----------|
| Controller calls a repository directly | Business rules and transactions skipped | `API_DOES_NOT_USE_REPOSITORIES` |
| Domain returns `ResponseEntity` / uses HTTP types | Core tied to the web | `DOMAIN_IS_WEB_AGNOSTIC` |
| Module A uses `moduleB.internal.Something` | Hidden coupling between features | Spring Modulith |
| Two modules depend on each other | Can't change or extract either one | Spring Modulith (cycle) |
| `@Autowired` on fields | Hidden dependencies, hard to test | `NO_FIELD_INJECTION` |
| Architecture only in a diagram or wiki | Erodes within months | Rules as tests ✅ |
| Rules nobody tests | May silently check nothing | Fixture tests ✅ |

> **Gap found and closed while writing this note:** because `api` may use `domain` types (to map
> entities to DTOs), the layer rule alone did *not* stop a controller from injecting a
> `JobRepository` (a domain interface). That's one of the most common shortcuts in Spring apps. The
> dedicated `API_DOES_NOT_USE_REPOSITORIES` rule was added, with the `ShortcutController` fixture
> proving it works.

## Interview questions

**Q: What is clean architecture's main rule?**
The dependency rule: source-code dependencies point inwards, toward the domain. Inner layers know
nothing about outer ones (web, database, frameworks).

**Q: Package by layer vs package by feature?**
By layer groups by technical role, scattering features. By feature (modules) keeps each business
capability together and lets internals stay hidden; layers then exist inside each feature.

**Q: Hexagonal vs layered?**
Hexagonal (ports & adapters) isolates the core behind explicit interfaces for all inputs and outputs,
with adapters for web and persistence. Layered is simpler. Many teams use a pragmatic mix, as Job Finder
does.

**Q: How do you stop architecture from eroding?**
Encode it as automated tests (ArchUnit, Spring Modulith) that fail the build, and record decisions
in ADRs.

**Q: What is Spring Modulith?**
A Spring project for modular monoliths: it detects modules from the package structure, verifies
boundaries and cycles, generates documentation, and supports event-based module communication.

**Q: What is an ADR?**
An Architecture Decision Record: a short, immutable document with the context, the decision and its
consequences, for one significant technical decision.
