# Job Finder — Learning Journal

This folder is my step-by-step journal for learning **Java** and **Spring** while building
the Job Finder project. Every concept I use in the code — and important related concepts I
learn but haven't used yet — gets written up here.

## How to read this folder

Read the notes in order. Each note builds on the previous one.

| #  | Note | What it covers | Status in code |
|----|------|----------------|----------------|
| 00 | [Project setup: JDK, Maven, pom.xml](00-project-setup.md) | JDK 25, Maven, `pom.xml`, folder layout, `.gitignore`, how to run | ✅ Applied |
| 01 | [Java essentials used in this project](01-java-essentials.md) | packages, classes, fields, getters/setters, `var`, `static`, annotations, casting, Java 25 `main` and `IO` | ✅ Applied |
| 02 | [Spring IoC container and beans](02-spring-ioc-and-beans.md) | IoC, DI, `ApplicationContext`, `@Configuration`, `@Bean`, `getBean`, bean names, singletons, errors | ✅ Applied |
| 03 | [Multiple beans, bean names and aliases](03-multiple-beans-and-naming.md) | Several beans of one type, `NoUniqueBeanDefinitionException`, `@Bean("name")`, aliases, `@Description` | ✅ Applied |
| 04 | [`@Component`, `@ComponentScan` and the bean lifecycle](04-component-scan-and-bean-lifecycle.md) | Stereotype annotations, scanning, init/destroy hooks and their order, `InitializingBean`/`DisposableBean`, `@PostConstruct`/`@PreDestroy` | ✅ Applied (annotations 🧪) |
| 05 | [Layers in a backend application](05-backend-layers.md) | Controller → Service → Repository, DTOs vs entities, layering rules, package-by-feature, Job Finder plan | 🧪 Verified in a demo |
| 06 | [The `@Import` annotation](06-import-annotation.md) | Modular configuration, importing configs and plain classes, `ImportSelector`, how `@Enable…` works, `@Import` vs `@ComponentScan` | 🧪 Verified in a demo |
| 07 | [Stereotype annotations](07-stereotype-annotations.md) | `@Component` family tree, `@Service`/`@Repository`/`@Controller`/`@RestController`/`@Configuration`, what each adds, custom stereotypes | ✅ `@Component`/`@Configuration`; others 🧪 |
| 08 | [Bean wiring (dependency injection)](08-bean-wiring.md) | `@Bean` method call vs parameter, lite mode, field/setter/constructor `@Autowired`, optional deps, circular dependencies | 🧪 Verified in a demo |
| 09 | [Autowiring in depth](09-autowiring.md) | Resolution algorithm, field vs parameter name matching, interfaces, collections + `@Order`, generics as qualifiers, static fields | 🧪 Verified in a demo |
| 10 | [Circular dependencies](10-circular-dependencies.md) | Why constructor cycles fail, early references, order-dependent failures, `@Lazy`/`ObjectProvider` workarounds, design fixes (extract, events) | 🧪 Verified in a demo |
| 11 | [`@Primary`, `@Qualifier`, and `@Qualifier` vs `@Primary`](11-primary-vs-qualifier.md) | Injection points, strict matching, qualifier labels and `List` filtering, custom qualifiers, side-by-side comparison | 🧪 Verified in a demo |
| 12 | [Programmatic bean registration](12-programmatic-bean-registration.md) | `registerBean`, `registerSingleton`, Spring 7 `BeanRegistrar`, `BeanDefinitionRegistryPostProcessor`, config-driven beans | 🧪 Verified in a demo |
| 13 | [Bean scopes (all six)](13-bean-scopes.md) | singleton, prototype, request, session, application, websocket; prototype-in-singleton trap and fixes; scoped proxies; custom scopes | ✅ singleton; others 🧪 |
| 14 | [Eager & lazy instantiation](14-eager-and-lazy-instantiation.md) | Default eager singletons, `@Lazy` placements, eager-needs-lazy trap, injection-point proxies, fail-fast trade-off, global lazy init | ✅ eager; `@Lazy` 🧪 |
| 15 | [`@SpringBootApplication`, Spring Boot setup, health endpoint](15-spring-boot-application-and-health.md) | What `@SpringBootApplication` is made of, auto-configuration, Boot 4 starters, Actuator health/liveness/readiness, tests | ✅ Applied |
| 16 | [`@RestController` (= `@Controller` + `@ResponseBody`)](16-rest-controller.md) | View names vs response body, message converters + Jackson 3, `ResponseEntity`, status codes, RFC 9457 Problem Details | 🧪 demo; Problem Details ✅ |
| 17 | [`@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestHeader`, `@RequestBody`](17-request-mapping-and-binding.md) | Routing conditions (405/406/415), overlapping-route trap, type conversion, required/optional, lists/enums/dates, headers, JSON binding and Jackson 3 defaults | 🧪 Verified in a demo |
| 18 | [API versioning](18-api-versioning.md) | FoodRush scenario, breaking vs non-breaking changes, 4 strategies with Spring 7 built-in versioning, deprecation/sunset headers, Job Finder decision | ✅ Path versioning applied + tested |
| 19 | [Clean architecture: structure, rules, enforcement](19-clean-architecture.md) | Dependency rule, layered vs hexagonal vs modular, Job Finder structure, Spring Modulith + ArchUnit rules, testing the rules, ADRs | ✅ Applied + enforced |
| 20 | [Spring Boot logging: console patterns, profiles, structured JSON](20-spring-boot-logging.md) | SLF4J/Logback, levels, pattern conversion words, ECS/Logstash/GELF, MDC, dev/prod profiles, testing logging in a separate JVM | ✅ Applied + tested |
| 21 | [Spring Boot DevTools](21-spring-boot-devtools.md) | Production-safe setup, 3 layers of protection, fast restart (two class loaders), Boot 4 changes (per-module defaults, LiveReload off), the test trap | ✅ Applied + guarded |
| 22 | [Working with a database in Java (JDBC)](22-working-with-a-database-in-java.md) | The typical JDBC steps, SQL injection, transactions, connection leaks, pooling (HikariCP), Spring `JdbcClient`, the data-access ladder | 🧪 Verified in a demo |
| 23 | [Spring Data vs Spring Data JPA](23-spring-data-vs-spring-data-jpa.md) | The Spring Data umbrella (release train 2026.1.0), Commons repositories, JPA/Hibernate stack, same repository on JPA vs JDBC, dirty checking, production settings | 🧪 Verified in a demo |
| 24 | [How Spring Data JPA and Hibernate are related](24-spring-data-jpa-and-hibernate.md) | Runtime trace: SimpleJpaRepository → EntityManager proxy → Hibernate SessionImpl → Hikari; save = persist/merge; who validates queries; who does what | 🧪 Verified in a demo |
| 25 | [Important components of Spring Data JPA](25-spring-data-jpa-components.md) | Entities, repositories, derived/@Query/@Modifying, Page vs Slice, projections, N+1 + @EntityGraph, Specifications, auditing, @Version, transactions, config | 🧪 Verified in a demo |
| 26 | [Important interfaces of Spring Data and Spring Data JPA](26-spring-data-interfaces.md) | Hierarchy and methods read from the jars, PagingAndSortingRepository ≠ CRUD, deprecations, @NoRepositoryBean, @RepositoryDefinition, keyset Window scrolling, Query by Example trap, getReferenceById, new Update/DeleteSpecification | 🧪 Verified in a demo |

**What's next:** see the [roadmap](roadmap.md) for planned topics, from Spring Core to the
full Job Finder app.

## Status legend

- ✅ **Applied** — the concept is in the project code right now.
- 🧪 **Learned, not applied** — I studied it and tested it, but it isn't in the code yet.
- 🔜 **Planned** — on the roadmap and not started.

## How each note is laid out

Each note follows the same structure, so it's easy to come back and revise:

1. **Goal** — what this step is for.
2. **Concepts** — the theory, in plain language.
3. **In this project** — the exact code, with file links.
4. **Run it / verify it** — commands and the real output.
5. **Common mistakes** — errors I saw or might see, and what they mean.
6. **Interview questions** — short Q&A for revision.

## Project timeline

| Date | Step | Notes |
|------|------|-------|
| 2026-11-19 | Starter project: Maven + Spring Context 7, first beans | 00, 01, 02 |
| 2026-11-19 | Fixes: `LuckyNumber` → `luckyNumber` (bean naming), close the context with try-with-resources, `getBean(name, type)` instead of a cast | 01 §9, §10, §13 · 02 §5, §6, §9 |
| 2026-11-19 | Three `Vehicle` beans, explicit names, aliases, `@Description`; fixed a `veh`/`veh2` print bug | 03 |
| 2026-11-19 | Notes committed to Git (removed `learning/` from `.gitignore`) | all |
| 2026-11-20 | `Vehicle` becomes a scanned `@Component` with `InitializingBean`/`DisposableBean`; added `jakarta.annotation-api`. Fixed: malformed `pom.xml`, wrong scan package, duplicate `Vehicle` beans, restored try-with-resources | 00 §3 · 04 |
| 2026-11-20 | Studied `@Component` vs `@Bean` in depth (when to use which) | 04 §1 |
| 2026-11-20 | Studied `BeanFactory` vs `ApplicationContext` (lazy vs eager, annotation processing, events) | 02 §11 |
| 2026-11-20 | Studied every fix for `NoUniqueBeanDefinitionException`: `@Primary`, `@Qualifier`, `@Fallback`, parameter names, `List`/`Map` | 03 §8 |
| 2026-11-20 | Studied every way to name a bean (incl. `URLParser` rule, name clashes, name generators) and `@Primary` in depth | 03 §9, §10 |
| 2026-11-20 | Studied backend layers (controller/service/repository, DTOs, package-by-feature) with a verified demo. Roadmap renamed to `roadmap.md` | 05 |
| 2026-11-20 | Studied `@Import` (configs, plain classes, `ImportSelector`, `@Enable…` internals, circular imports) | 06 |
| 2026-11-20 | Studied stereotype annotations (meta-annotations, runtime classes, custom `@UseCase`) | 07 |
| 2026-11-20 | Studied bean wiring: method call vs parameter, lite mode, three injection styles, optional deps, circular deps | 08 |
| 2026-11-20 | Studied autowiring in depth (resolution order, collections, generics, edge cases) | 09 |
| 2026-11-20 | Studied circular dependencies: causes, how Spring resolves some, workarounds, design fixes | 10 |
| 2026-11-20 | Studied `@Primary` vs `@Qualifier` (injection points, labels, custom qualifiers, precedence) | 11 |
| 2026-11-20 | Studied programmatic bean registration incl. Spring 7 `BeanRegistrar` | 12 |
| 2026-11-20 | Studied all six bean scopes (web scopes simulated), scope mixing, scoped proxies, `thread` scope | 13 |
| 2026-11-20 | Studied eager vs lazy instantiation (`@Lazy` placements, traps, fail-fast) | 14 |
| 2026-11-20 | Singleton vs prototype in depth: per-injection instances, `destroyBean`, runtime args, singleton data-leak demo | 13 §10 |
| 2026-11-20 | **Moved to Spring Boot 4.2.0**: `JobFinderApplication`, Actuator health with liveness/readiness probes, 5 HTTP tests. Spring Core demo code removed (kept at tag `spring-core-basics`) | 15 · banners in 00–04 |
| 2026-11-20 | Studied `@RestController` with a verified scratch API. **Enabled RFC 9457 Problem Details** for all errors + test | 16 |
| 2026-11-20 | Studied request mapping and binding annotations in depth (40+ verified requests, 3 traps root-caused) | 17 |
| 2026-11-20 | Studied API versioning (all 4 strategies verified with Spring 7). **Applied path versioning `/api/v1`** + `ApiVersioningTests` (incl. Actuator not affected) | 18 |
| 2026-11-20 | **Adopted modular monolith + clean layers** (ADR 0001), enforced by Spring Modulith 2.2 + ArchUnit 1.5.1 with fixture-tested rules (28 tests). Added ADRs 0001–0003 | 19 |
| 2026-11-20 | **Logging per environment:** `dev` profile (compact pattern, DEBUG, health details) via `spring-boot:run`, `prod` profile (ECS JSON). ADR 0004, `ProfileConfigurationTests` in a child JVM (30 tests) | 20 |
| 2026-11-20 | **DevTools** added (runtime + optional). Verified: absent from the jar, inactive with `java -jar`, 0.172 s restart in dev. Fixed `ProfileConfigurationTests` so the prod check runs without DevTools (guard + mutation check) | 21 |
| 2026-11-20 | Studied JDBC fundamentals with H2 + HikariCP + Spring JdbcClient (SQL injection, rollback, leaks, 11× pooling speed-up verified) | 22 |
| 2026-11-20 | Studied Spring Data vs Spring Data JPA: identical repository run on JPA and JDBC, generated SQL compared, dirty checking verified | 23 |
| 2026-11-20 | Traced Spring Data JPA → Hibernate at runtime (real classes behind proxies, persist vs merge SQL, startup query validation) | 24 |
| 2026-11-20 | Studied Spring Data JPA components with real SQL: projections, Page vs Slice, N+1 (1+3) fixed by @EntityGraph, Specifications, auditing, optimistic locking | 25 |
| 2026-11-21 | Studied Spring Data / JPA interfaces from the 4.2 jars; verified keyset scrolling, QBE primitive trap, Spring Data JPA 4 Update/DeleteSpecification | 26 |
