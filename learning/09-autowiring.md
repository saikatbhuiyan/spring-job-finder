# 09 — Autowiring in Spring

## Goal

[Note 08](08-bean-wiring.md) covered **where** Spring injects (constructor, setter, field)
and wiring inside `@Configuration`. This note goes deeper into **`@Autowired`** itself:
how Spring *finds* the bean to inject, what it can inject (interfaces, collections,
generics), and the surprising edge cases. Everything here was verified in a scratch
program on Spring 7.0.10.

Status: 🧪 learned and verified; not yet in the project code.

---

## 1. What "autowiring" means

**Autowiring** = Spring **automatically resolves** which bean satisfies a dependency, so you
don't have to name it. You declare *what type you need*; Spring finds a matching bean.

```java
@Component
class UsesInterface {
    private final Notifier notifier;
    UsesInterface(Notifier notifier) { this.notifier = notifier; }   // "I need some Notifier"
}
```

- **Explicit wiring:** *you* say which bean: `new Person(vehicle())` or `@Qualifier("bmw")`.
- **Autowiring:** *Spring* decides, based on type (then the other rules in §3).

> **History:** in old XML configuration you chose an "autowire mode" for each bean: `no`,
> `byName`, `byType` or `constructor`. Modern annotation-based Spring does
> **by type first, then narrows down** (§3), and you mark injection points with `@Autowired` or
> a single constructor.

---

## 2. Where `@Autowired` can go

| Target | Example | Notes |
|--------|---------|-------|
| Constructor | `@Autowired Person(Vehicle v)` | Optional if it's the **only** constructor (note 08) |
| Field | `@Autowired Vehicle vehicle;` | Avoid (note 08) |
| Setter | `@Autowired void setVehicle(Vehicle v)` | For optional/changeable dependencies |
| **Any method** | `@Autowired void init(Notifier n, List<Importer> all)` | Spring calls it after construction, with **every** parameter resolved |
| ❌ Static field | `@Autowired static Notifier n;` | **Silently ignored**, stays `null` |

Verified:

```text
@Autowired method: init(email, 1 importers)       ← any method name, multiple parameters
static field: null
INFO: Autowired annotation is not supported on static fields: ...Methods.staticField
```

Static fields belong to the **class**, not to a bean instance, so Spring refuses to touch
them. It only logs an INFO line, so it's easy to miss.

---

## 3. How Spring picks the bean (the resolution algorithm)

```text
                   ┌──────────────────────────────┐
                   │ 1. Find beans of the TYPE     │  (also matches interfaces,
                   │    (and generic type)         │   subclasses, generics)
                   └──────────────┬───────────────┘
            0 found               │ 1 found              2+ found
   ┌──────────────────┐          ▼             ┌───────────────────────────────┐
   │ required? → FAIL │     inject it ✅       │ 2. @Qualifier at injection?  ──▶ use it
   │ NoSuchBean…      │                        │ 3. exactly one @Primary?     ──▶ use it
   │ optional → null/ │                        │ 4. (no @Fallback on others…)  │
   │ Optional.empty   │                        │ 5. field/param NAME matches  ──▶ use it
   └──────────────────┘                        │    a bean name?               │
                                               │ else → NoUniqueBean… FAIL     │
                                               └───────────────────────────────┘
```

Steps 2–4 are covered in [note 03 §8](03-multiple-beans-and-naming.md). Step 5 has a surprise:

### Name matching: fields vs constructor parameters (verified)

Two `Vehicle` beans named `audi` and `bmw`:

```java
@Component class ByFieldName { @Autowired Vehicle bmw; }                    // field named "bmw"
@Component class ByParamName { ByParamName(Vehicle bmw) { ... } }          // parameter named "bmw"
```

```text
field got BMW                                  ✅ works
param got → NoUniqueBeanDefinitionException     ❌ fails (in this project's build)
```

**Why?** Java always keeps **field names** in the compiled `.class` file, so Spring can read
them. **Parameter names** are thrown away unless you compile with `-parameters` (note 03 §8,
Fix 5). This project's `pom.xml` doesn't set that flag; Spring Boot's parent POM does.

**Lesson:** never rely on names to choose a bean. Use `@Qualifier`, which works the same
everywhere.

---

## 4. Autowiring by interface ✅ (the most important use)

```java
public interface Notifier { String send(); }

@Component public class EmailNotifier implements Notifier { ... }

@Component public class UsesInterface {
    UsesInterface(Notifier n) { ... }        // asks for the INTERFACE
}
```

Verified: `UsesInterface got EmailNotifier`.

The class depends on **what** it needs (`Notifier`), not **which** implementation. Later
you can swap `EmailNotifier` for `SmsNotifier` without touching `UsesInterface`. This is the
**Dependency Inversion Principle** (the "D" in SOLID), and it's why services usually depend on
interfaces such as repositories.

---

## 5. Autowiring collections (verified)

Three implementations of `Importer`:

```java
@Component @Order(2) class LinkedIn  implements Importer {}
@Component @Order(1) class Indeed    implements Importer {}
@Component @Order(3) class Glassdoor implements Importer {}

@Component class AllImporters {
    @Autowired List<Importer> list;
    @Autowired Importer[] array;
    @Autowired Set<Importer> set;
    @Autowired Map<String, Importer> map;
}
```

```text
List  = [Indeed, LinkedIn, Glassdoor]     ← sorted by @Order (1, 2, 3)
array = [Indeed, LinkedIn, Glassdoor]     ← sorted by @Order
Set   = [Glassdoor, LinkedIn, Indeed]     ← registration order; @Order IGNORED
Map   = [...Glassdoor, ...LinkedIn, ...Indeed]   ← bean name → bean; registration order
```

- `@Order(n)` (lower = earlier) only affects **`List` and arrays**.
- `Map` keys are **bean names**. (Here they're fully-qualified because the test classes were
  `@Import`ed; see note 06.)
- If **no** bean of the type exists, a required `List` injection fails; use
  `Optional<List<…>>` or `ObjectProvider` if none is a valid situation.

**Job Finder use:** `List<JobImporter>` to run every job-board importer in a defined order,
the classic **strategy / plugin pattern** with zero `if/else`.

---

## 6. Generics act as a qualifier (verified)

```java
public interface Repo<T> { ... }
@Component class JobRepo     implements Repo<Job>     { ... }
@Component class CompanyRepo implements Repo<Company> { ... }

@Component class UsesRepo {
    UsesRepo(Repo<Company> repo) { ... }      // two Repo beans exist…
}
```

```text
Repo<Company> got CompanyRepo      ← no @Qualifier needed
```

Spring matches the **full generic type**, not just `Repo`. Spring Data repositories
(`JpaRepository<Job, Long>`) rely on this.

---

## 7. Optional autowiring (recap from note 08, plus one detail)

| Declaration | Bean missing → |
|-------------|----------------|
| `@Autowired(required = false)` field | stays `null` |
| `@Autowired(required = false)` **method/setter** | **the method is not called at all** (verified: `not called`) |
| `Optional<T>` parameter | `Optional.empty` |
| `ObjectProvider<T>` → `getIfAvailable()` | `null` |

---

## 8. `@Autowired` alternatives you may see

| Annotation | From | Notes |
|------------|------|-------|
| `@Autowired` | Spring | Supports `required = false` |
| `@Inject` | Jakarta Inject (`jakarta.inject`, extra dependency) | Standard equivalent of `@Autowired` (no `required` attribute) |
| `@Resource(name = "…")` | Jakarta Annotations (we have `jakarta.annotation-api`) | Looks up **by name first**, then by type |
| *(nothing)* | — | Single constructor: no annotation needed ✅ |

Not verified in this project (the `@Inject` dependency isn't present). They're listed so you
recognise them in other codebases.

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `@Autowired` on a `static` field | Silently `null` (only an INFO log) |
| Relying on parameter names to pick a bean | Works in Spring Boot, fails in plain Maven builds |
| Expecting `@Order` to sort a `Set` or `Map` | It doesn't; only `List` and arrays |
| Injecting a concrete class instead of an interface | Harder to swap and test |
| `@Autowired(required = false)` on a setter, then using the field | The setter never ran, so the field is `null` |
| Two implementations of an interface, no `@Primary`/`@Qualifier` | `NoUniqueBeanDefinitionException` |

## Interview questions

**Q: What is autowiring?**
Spring automatically resolving and injecting a matching bean for a dependency, by type and then
narrowing down with qualifiers, `@Primary`, or names.

**Q: How does `@Autowired` resolve a dependency?**
By type (including generics). If several match: `@Qualifier`, then `@Primary`, then a name match
with the field/parameter name; otherwise `NoUniqueBeanDefinitionException`.

**Q: Can you autowire a `static` field?**
No. Spring ignores it (logs "not supported on static fields") and it stays `null`.

**Q: How do you inject all implementations of an interface?**
`List<Interface>` (ordered by `@Order`), arrays, `Set`, or `Map<String, Interface>` (bean name →
bean).

**Q: Why autowire interfaces rather than classes?**
Loose coupling: the implementation can change without touching the consumer (Dependency
Inversion), and tests can pass fakes.

**Q: `@Autowired` vs `@Inject` vs `@Resource`?**
`@Autowired` is Spring's (has `required`). `@Inject` is the Jakarta standard equivalent.
`@Resource` resolves by name first.
