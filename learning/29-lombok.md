# 29 — Lombok: Reduce Boilerplate (and Know Its Costs)

## Goal

Java classes need a lot of repetitive code: getters, constructors, `equals`/`hashCode`, `toString`, loggers.
**Project Lombok** generates that code at **compile time** from annotations. This note covers how it works,
how to set it up on **Java 25 + Spring Boot 4.2** (where the obvious setup silently fails), every common
annotation, how it interacts with Spring and JPA, and what it costs. It ends with the decision for Job
Finder.

Verified in a scratch app (Boot 4.2.0, Lombok **1.18.48** managed by Boot, JDK 25.0.1, Hibernate 7.4.15, H2).
Status: 🧪 verified; **not added to the project yet** (see §10).

---

## 1. What Lombok actually does

```text
 Your source (.java)           javac + Lombok annotation processor            Bytecode (.class)
 @Value                        Lombok edits the compiler's syntax tree        constructor, getters,
 class Money { long amount; }  (AST) *during* compilation                    equals, hashCode, toString
```

- Lombok runs **inside the compiler**. After compilation the generated methods are ordinary bytecode, and at
  runtime there is **no Lombok and no reflection**.
- Most Lombok annotations have **`SOURCE` retention** (verified: `lombok.Data` → `RetentionPolicy.SOURCE`),
  so they're **gone** from the `.class` files. Tools that read bytecode (ArchUnit, Spring) never see them.
- Lombok is a **build-time dependency only**. It should not end up in the production jar (§2).

Verified with `javap` on a 2-field `@Value` class:

```java
@Value
public class Money { long amountMinor; String currency; }
```

```text
public final class demo.Money {
  private final long amountMinor;
  private final java.lang.String currency;
  public demo.Money(long, java.lang.String);
  public long getAmountMinor();
  public java.lang.String getCurrency();
  public boolean equals(java.lang.Object);
  public int hashCode();
  public java.lang.String toString();
}
```

Two lines of source became a final, immutable value class.

---

## 2. Setup on Java 25 + Spring Boot 4.2 (verified)

### ⚠️ Adding the dependency alone is **not** enough on JDK 23+

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

With only this, the build **failed**:

```text
[ERROR] App.java:[5,77] constructor Money in class demo.Money cannot be applied to given types;
  required: no arguments
  found:    int,java.lang.String
```

Lombok **never ran**, so `@Value` generated nothing and the class only had the default constructor.
Since **JDK 23, javac no longer runs annotation processors it finds on the classpath** unless you
configure them explicitly. The error doesn't mention Lombok at all, which makes it confusing.

### ✅ The working setup: register Lombok as an annotation processor

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>   <!-- 1.18.48, managed by Boot 4.2 -->
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The build then passed and `javap` showed the generated methods (§1).

### `<optional>true</optional>` keeps Lombok out of the production jar (verified)

| Dependency declared as | `BOOT-INF/lib/` in the Boot jar |
|------------------------|----------------------------------|
| `<optional>true</optional>` | **no Lombok** ✅ |
| plain (no `optional`) | `lombok-1.18.48.jar` shipped to production ✗ |

The Spring Boot Maven plugin leaves optional dependencies out of the fat jar. (`<scope>provided</scope>`
works too.)

### The JDK 25 warning

Every compile printed:

```text
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by lombok.permit.Permit
```

Lombok works by reaching into **javac internals**, which aren't a public API. That's why each new JDK
needs a matching Lombok release, and why the JDK now warns about it. Keep this in mind (§9).

### IDE

IntelliJ IDEA bundles Lombok support. Enable *Settings → Build → Compiler → Annotation Processors → Enable
annotation processing* if generated methods show as red.

---

## 3. The annotations

| Annotation | Generates | Use? |
|------------|-----------|------|
| `@Getter` / `@Setter` | Getters / setters (class or field level) | `@Getter` ✅; `@Setter` ⚠️ breaks encapsulation of domain objects |
| `@ToString` | `toString()` with all fields | ✅ with `onlyExplicitlyIncluded` on entities (§6) |
| `@EqualsAndHashCode` | `equals`/`hashCode` from all fields | ⚠️ never on JPA entities (§6) |
| `@NoArgsConstructor` | No-arg constructor (`access = PROTECTED` possible) | ✅ for JPA entities |
| `@RequiredArgsConstructor` | Constructor for `final` (and `@NonNull`) fields | ✅ for Spring constructor injection (§4) |
| `@AllArgsConstructor` | Constructor for all fields | ⚠️ parameter order = field order; reordering fields silently swaps arguments |
| `@Data` | `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor` | ❌ on entities (§6); mostly replaced by records |
| `@Value` | Immutable class: `private final` fields, getters, all-args ctor, equals/hashCode/toString, `final` class | Use a **record** instead on Java 16+ (§7) |
| `@Builder` | `Job.builder().title(…).build()` | ✅ for objects with many optional parts; watch `@Builder.Default` (§5) |
| `@With` | `withSalary(…)` copy methods for immutable objects | Occasionally |
| `@Slf4j` | `private static final Logger log = LoggerFactory.getLogger(…)` | ✅ saves one line |
| `@NonNull` | A null check that throws `NullPointerException` | Prefer explicit validation or `Objects.requireNonNull` with a message |
| `@SneakyThrows` | Throws checked exceptions without declaring them | ❌ hides exceptions from callers |
| `val` / `var` | Local type inference | ❌ obsolete; Java has had `var` since 10 |

---

## 4. Lombok with Spring: constructor injection (verified)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class JobAlertService {
    private final Notifier notifier;          // final → constructor parameter
    private final BadJobRepository jobs;

    public String alert(String title) {
        log.info("Sending alert for {}", title);
        return notifier.send("New job: " + title);
    }
}
```

`delombok` (Lombok's "show me the generated code" tool, `java -jar lombok.jar delombok`) printed:

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JobAlertService.class);
…
@lombok.Generated
public JobAlertService(final Notifier notifier, final BadJobRepository jobs) {
    this.notifier = notifier;
    this.jobs = jobs;
}
```

That's the same constructor injection we write by hand (note 09). Spring sees one constructor and
autowires it.

### ⚠️ `@Qualifier` on a field is **not** copied to the constructor (verified)

With two `Notifier` beans (`email`, `sms`):

```java
@RequiredArgsConstructor
public class JobAlertService {
    @Qualifier("sms") private final Notifier notifier;
```

```text
APPLICATION FAILED TO START
Parameter 0 of constructor in demo.JobAlertService required a single bean, but 2 were found:
…
This may be due to missing parameter name information
Ensure that your compiler is configured to use the '-parameters' flag.
```

Lombok generated `JobAlertService(Notifier notifier, …)` **without** `@Qualifier`. Spring saw an
unqualified parameter and two candidates (note 11). Spring's hint about `-parameters` is **misleading**
here: the real cause is the missing annotation.

**Fix: a `lombok.config` file in the project root:**

```properties
config.stopBubbling = true
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```

After that, delombok showed `public JobAlertService(@Qualifier("sms") final Notifier notifier, …)` and the
app ran: `=> SMS: New job: Java Dev` ✅.

---

## 5. ⚠️ `@Builder` ignores field initializers (verified)

```java
@Builder @ToString
public class JobPosting {
    private String title;
    private String status = "OPEN";                    // looks like a default …
    @Builder.Default private int maxApplicants = 100;  // … this one really is
}

JobPosting.builder().title("Java Dev").build();
```

```text
=> JobPosting(title=Java Dev, status=null, maxApplicants=100)
```

`status` is **`null`**, not `"OPEN"`. Lombok warns at compile time, but warnings are easy to miss:

```text
[WARNING] JobPosting.java:[7,18] @Builder will ignore the initializing expression entirely.
If you want the initializing expression to serve as default, add @Builder.Default.
```

Treat compiler warnings as errors in CI, or always use `@Builder.Default`.

---

## 6. ⚠️ Lombok on JPA entities: what breaks (verified)

Entities are the **riskiest place** for Lombok. A bidirectional `BadCompany` ↔ `BadJob` mapping with `@Data`:

```java
@Entity @Data @NoArgsConstructor
public class BadCompany {
    @Id @GeneratedValue Long id;
    String name;
    @OneToMany(mappedBy = "company") List<BadJob> jobs = new ArrayList<>();
}

@Entity @Data @NoArgsConstructor
public class BadJob {
    @Id @GeneratedValue Long id;
    String title;
    @ManyToOne(fetch = FetchType.LAZY) BadCompany company;
}
```

| Action | Result (verified) | Why |
|--------|-------------------|-----|
| `company.toString()` | **`StackOverflowError`** | Company prints its jobs → each job prints its company → … forever |
| `hashCode()` on **new** (unsaved) company + job | **`StackOverflowError`** | Same loop through `jobs` ↔ `company` |
| `hashCode()` after save | Returned a number | Hibernate had replaced the `ArrayList` with `org.hibernate.collection.spi.PersistentBag` (verified), whose `hashCode` is identity-based. It only "worked" by accident |
| `job.toString()` **outside a transaction** | **`LazyInitializationException: Could not initialize proxy [demo.BadCompany#1] - no session`** | `toString` touched the lazy `company` proxy. A log line can crash a request |
| Put a new `@Data` `Tag` into a `HashSet`, then `save()` it | before: `contains=true`; after: `id=1`, **`contains=false`**, `remove(tag)=false`, size still 1 | `@EqualsAndHashCode` includes `id`. Saving set the id, so the hash changed and the set lost the object |

So, on entities:

- **No `@Data`, no `@EqualsAndHashCode`, no `@Value`.**
- **No `@ToString` with relations.**
- **No `@Setter`**: changes go through domain methods (`job.rename(…)`, `job.close()`), note 19.

### ✅ A safe entity (verified)

```java
@Entity
@Getter                                             // read access only
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA needs it; app code must use the real constructor
@ToString(onlyExplicitlyIncluded = true)            // only fields you opt in; never relations
public class Job {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @ToString.Include private Long id;
    @ToString.Include private String title;
    @ManyToOne(fetch = FetchType.LAZY) private Company company;

    public Job(String title, Company company) { … }

    public void rename(String newTitle) {           // behavior instead of a setter
        if (newTitle == null || newTitle.isBlank()) throw new IllegalArgumentException("title required");
        this.title = newTitle;
    }

    // Hand-written: equal only when both have the same non-null id; hashCode constant per class
    @Override public boolean equals(Object o) { return this == o || (o instanceof Job j && id != null && id.equals(j.getId())); }
    @Override public int hashCode() { return Job.class.hashCode(); }
}
```

```text
company.toString()=Company(id=1, name=Globex)  job.toString()=Job(id=1, title=Go Developer)
after save contains=true  reloaded equals=true
toString outside tx (lazy company not touched): Job(id=1, title=Go Developer)
```

The constant `hashCode` looks odd, but it's the standard choice for entities. The hash stays the same when
the id is assigned, so `HashSet`s keep working.

### ⚠️ Use `getId()`, not the `id` field, of the *other* object (verified)

`job.getCompany()` returns a lazy **Hibernate proxy** (`Company$HibernateProxy`), a generated subclass
whose own fields are **empty**. Comparing a loaded `Company` with that proxy for the same row:

| `equals` reads the other object's id via | Result |
|-------------------------------------------|--------|
| field `c.id` | **`false`**: the proxy's field is `null` |
| getter `c.getId()` | **`true`**: the proxy answers `getId()` without even loading the row |

`instanceof` works with proxies (the proxy is a subclass). Field access doesn't. I caught this bug while
writing this note, and it's exactly the kind of thing generated `equals` methods get wrong too.

---

## 7. Records vs Lombok `@Value` (Java 25)

```java
public record Money(long amountMinor, String currency) {
    public Money {                                   // compact constructor: validation
        if (amountMinor < 0) throw new IllegalArgumentException("negative amount");
        Objects.requireNonNull(currency, "currency");
    }
}
```

| | `record` (Java 16+) | Lombok `@Value` |
|-|---------------------|-----------------|
| Needs a library or annotation processor | ❌ none, it's the language | ✅ Lombok + build config |
| Immutable, final, equals/hashCode/toString | ✅ | ✅ |
| Accessors | `amountMinor()` | `getAmountMinor()` |
| Validation | Compact constructor | Write the constructor yourself |
| Jackson 3 / Spring binding / JPQL `select new` | ✅ supported (note 28 §4) | ✅ |
| JPA entity | ❌ (entities must be mutable with a no-arg ctor) | ❌ |
| Builder | ❌ (write one, or use `@Builder` on the record) | `@Builder` |

**On Java 25, records replace most uses of `@Value` and `@Data`** for DTOs, request/response bodies,
commands, events, query projections and value objects. Lombok's remaining niche is **entities** (getters,
protected constructor, safe `toString`), **builders**, and `@Slf4j`.

---

## 8. `lombok.config`: project-wide rules (verified)

Put it in the project root (next to `pom.xml`):

```properties
config.stopBubbling = true                       # don't inherit config from parent directories
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier   # §4
lombok.addLombokGeneratedAnnotation = true       # mark generated code with @lombok.Generated
lombok.data.flagUsage = error                    # ban @Data
lombok.setter.flagUsage = error                  # ban @Setter
```

- **`addLombokGeneratedAnnotation`**: `@lombok.Generated` has **`CLASS` retention** (verified), so it stays
  in the bytecode. **JaCoCo** excludes methods annotated with it, so generated getters don't distort test
  coverage.
- **`flagUsage = error`** turned `@Data` into a compile error (verified):

  ```text
  [ERROR] BadCompany.java:[5,9] Use of @Data is flagged according to lombok configuration.
  ```

  This is the right way to enforce Lombok rules. **ArchUnit can't do it**, because `@Data` has `SOURCE` retention
  and doesn't exist in the bytecode ArchUnit inspects.

---

## 9. The costs: why many teams limit or avoid Lombok

| Cost | Detail |
|------|--------|
| **Relies on javac internals** | Not a public API. Every new JDK needs a Lombok update, and JDK 25 already warns (`sun.misc.Unsafe`, §2) |
| **Build fragility** | The JDK 23+ processor change broke the "just add the dependency" setup (§2) |
| **Hidden code** | You can't read, debug or set a breakpoint in a generated method; use `delombok` to see it |
| **Easy misuse** | `@Data` on entities, `@AllArgsConstructor` argument order, `@Builder` defaults, `@Qualifier` (§4–§6) |
| **Tooling** | Every tool (IDE, static analysis, coverage) must understand it |
| **Escape hatch** | `delombok` turns everything into plain Java if you ever remove Lombok |

On the other side, Lombok is **very common in Spring codebases**. Knowing it, and especially knowing its
traps, is expected in interviews and on real teams.

---

## 10. Decision for Job Finder

**Not added now.** The project has no entities or DTOs yet, so there's nothing for it to simplify. Adding
an unused dependency and build configuration isn't production practice.

**Policy when persistence lands** (recorded as an ADR at that point):

1. **Records** for everything immutable: DTOs, API request/response bodies, commands, events, projections,
   value objects (`Money`, `Salary`).
2. **Plain constructors** in Spring components. With 2–3 dependencies, a hand-written constructor is
   clearer than `@RequiredArgsConstructor` and avoids the `@Qualifier` trap.
3. **Lombok only if entity boilerplate becomes significant**, and then only `@Getter`,
   `@NoArgsConstructor(access = PROTECTED)` and `@ToString(onlyExplicitlyIncluded = true)`, with the
   `lombok.config` above (bans `@Data`/`@Setter`, generated-code annotation, `<optional>` dependency,
   processor path).

**Why:** Java 25 already covers most of what Lombok was invented for. Every remaining use costs build
fragility on each JDK upgrade.

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| Only adding the dependency on JDK 23+ | Lombok silently doesn't run; confusing "constructor … required: no arguments" errors (verified) |
| Lombok not `optional`/`provided` | Lombok jar shipped in production (verified) |
| `@Data` on JPA entities | `StackOverflowError`, `LazyInitializationException`, entities lost from `HashSet` (all verified) |
| `@Qualifier` on fields with `@RequiredArgsConstructor` | `NoUniqueBeanDefinitionException`-style startup failure (verified); fix with `copyableAnnotations` |
| Field initializer with `@Builder` | Value silently `null`/`0` (verified); use `@Builder.Default` |
| `@Setter` on domain objects | Business rules bypassed; use domain methods |
| `@AllArgsConstructor` used by other code | Reordering fields swaps arguments of the same type without a compile error |
| Entity `equals` reading `other.id` directly | `false` against a Hibernate proxy of the same row (verified); use `other.getId()` |
| `@Value`/`@Data` for DTOs on modern Java | Unnecessary dependency; use records |
| Trying to ban Lombok annotations with ArchUnit | Impossible: `SOURCE` retention (verified); use `lombok.config` `flagUsage` |

## Interview questions

**Q: How does Lombok work?**
It is an annotation processor that modifies the compiler's syntax tree during compilation, adding methods and
constructors. The output is ordinary bytecode, with no Lombok at runtime.

**Q: Why shouldn't you use `@Data` on JPA entities?**
Its `toString`/`hashCode` traverse relations (infinite recursion on bidirectional mappings, lazy-loading
exceptions), and its `equals`/`hashCode` include the mutable generated id, so entities get lost in hash-based
collections after being saved. It also adds public setters for every field.

**Q: How should `equals`/`hashCode` be implemented for an entity?**
Equal when both ids are non-null and equal (`instanceof` check, reading the other id through `getId()` so it
works with Hibernate proxies), with a constant per-class `hashCode`, so the hash doesn't change when the id is
assigned.

**Q: `@Value` vs a Java record?**
Both make immutable value classes. A record is part of the language, needs no tooling, and is supported by
Jackson, Spring and JPQL. Prefer records on Java 16+.

**Q: How do you use Lombok with Spring constructor injection?**
`@RequiredArgsConstructor` with `final` fields. If you use `@Qualifier` on fields, configure
`lombok.copyableAnnotations` so it's copied to the constructor parameters.

**Q: What is `@Builder.Default` for?**
`@Builder` ignores field initializers. `@Builder.Default` makes the initializer the builder's default.

**Q: What are the downsides of Lombok?**
It depends on compiler internals (upgrades can break it), hides code from readers and debuggers, needs build
and IDE configuration, and has easy-to-misuse annotations. `delombok` can remove it.
