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
