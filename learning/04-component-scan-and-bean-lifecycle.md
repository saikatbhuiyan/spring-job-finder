# 04 — `@Component`, `@ComponentScan` and the Bean Lifecycle

> **History note (2026-11-20):** the project has since moved to **Spring Boot** (see
> [note 15](15-spring-boot-application-and-health.md)). The plain-Spring code shown in this note
> (`Main`, `Vehicle`, `ProjectConfig`) was removed from `main` and is preserved at the git tag
> [`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).
> The concepts are unchanged; Spring Boot builds on all of them.

## Goal

Two new ideas:

1. **Stereotype annotations and component scanning**: let Spring *find* your classes and
   create the beans itself, instead of writing a `@Bean` method for each one.
2. **The bean lifecycle**: run your own code right after Spring creates a bean (*init*)
   and right before it throws the bean away (*destroy*).

Builds on [note 02](02-spring-ioc-and-beans.md) and [note 03](03-multiple-beans-and-naming.md).

---

## Part A: `@Component` and `@ComponentScan`

### 1. Two ways to create beans

| | `@Bean` method (notes 02–03) | `@Component` + scan (this note) |
|-|------------------------------|---------------------------------|
| Where | A method in a `@Configuration` class | On the class itself |
| Who calls `new` | You, inside the method | Spring |
| Bean name | Method name | Class name with a lowercase first letter (`Vehicle` → `vehicle`; but `URLParser` stays `URLParser`, see note 03 §9) |
| Best for | Classes you **can't** annotate: library classes, or ones that need custom setup | **Your own** classes |

In real Spring/Spring Boot apps, most beans are your own classes, so `@Component` (and
its specialised forms) is the most common way.

#### `@Component` vs `@Bean` in depth

| Question | `@Component` | `@Bean` |
|----------|--------------|---------|
| What is annotated? | A **class** | A **method** (inside a `@Configuration` class) |
| How does Spring find it? | `@ComponentScan` must cover the class's package | Spring reads the `@Configuration` class you register |
| Who creates the object? | Spring calls the constructor | **Your code** inside the method (`new …`, a builder, a factory) |
| Can it be used on library classes? | ❌ You can't add annotations to code you don't own | ✅ Any class at all (verified with `StringBuilder`) |
| How many beans per class? | **One** per class | As many as you write methods (note 03 had three `Vehicle`s) |
| Default name | `vehicle` (class name, lowercase first letter) | Method name |
| Custom name | `@Component("v8")` (verified) | `@Bean("ferrari")` |
| Setup logic | Constructor + lifecycle hooks | Any Java code in the method body: if/else, reading config, calling setters |
| Couples the class to Spring? | Yes, the class carries a Spring annotation | No, the class stays plain Java and the config class knows about Spring |

**Decision rule:**

1. **It's your own class** (`JobService`, `JobRepository`, `JobController`) → `@Component`
   or a stereotype (`@Service`, `@Repository`, `@RestController`).
2. **It comes from a library**, or needs **custom construction** (a builder, settings,
   several differently configured instances) → a `@Bean` method.

Typical `@Bean` examples you'll write later in Job Finder (not in the code yet 🔜):

```java
@Configuration
class AppConfig {
    @Bean
    PasswordEncoder passwordEncoder() {          // Spring Security class: can't annotate it
        return new BCryptPasswordEncoder();
    }

    @Bean
    RestClient jobBoardClient() {                // library class, built with custom settings
        return RestClient.builder().baseUrl("https://api.example.com").build();
    }
}
```

⚠️ **Don't use both for the same class.** A `@Component` class that *also* has `@Bean`
methods returning it gives you several beans (this project briefly had 4 `Vehicle`s; §4).

### 2. In this project

[Vehicle.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/beans/Vehicle.java):

```java
package com.shahabuddin.beans;

@Component
public class Vehicle implements InitializingBean, DisposableBean { ... }
```

[ProjectConfig.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/config/ProjectConfig.java):

```java
@Configuration
@ComponentScan(basePackages = "com.shahabuddin.beans")
public class ProjectConfig { ... }
```

- **`@Component`** marks the class: "Spring, please create a bean of this class."
- **`@ComponentScan`** tells Spring **where to look**. Spring searches that package
  (and its sub-packages) for `@Component` classes.

**Both are required.** `@Component` on its own does nothing if no scan covers its package.

### 3. Stereotype annotations

`@Component` is the general one. Three specialised versions say *what role* a class plays.
All of them are picked up by the scan:

| Annotation | Layer | Extra behavior |
|------------|-------|----------------|
| `@Component` | Any | — |
| `@Service` | Business logic | None (it documents intent) |
| `@Repository` | Data access | Translates database exceptions into Spring's `DataAccessException` |
| `@Controller` / `@RestController` | Web | Handles HTTP requests (Spring MVC) |

In Job Finder we'll use `JobService` (`@Service`), `JobRepository` (`@Repository`) and
`JobController` (`@RestController`).

**In depth** (meta-annotations, `@Controller` vs `@RestController`, custom stereotypes),
all verified: [note 07](07-stereotype-annotations.md).

### 4. ✅ Two bugs found and fixed (2026-11-20)

#### Bug 1: the scan pointed at the wrong package

The first version was:

```java
@ComponentScan(basePackages = "com.shahabuddin.config")   // ❌
```

`Vehicle` lives in `com.shahabuddin.beans`, so Spring never found its `@Component`.
**There was no error and no warning.** The annotation was silently ignored. Fixed:

```java
@ComponentScan(basePackages = "com.shahabuddin.beans")    // ✅
```

**Lesson:** a scan only finds classes **inside** the package you name (and its
sub-packages). If a `@Component` bean "doesn't exist", check the scan package first.

> Spring Boot's `@SpringBootApplication` scans the package of the main class and everything
> below it. That's why Boot projects put the main class in the root package
> (`com.shahabuddin`).

#### Bug 2: four `Vehicle` beans, so `getBean(Vehicle.class)` crashed

`ProjectConfig` still had the three `@Bean` Vehicle methods from note 03. Even with the
scan fixed, there would be **four** `Vehicle` beans (verified):

```text
Vehicle beans: vehicle,vehicle1,vehicle2,ferrari
```

So `context.getBean(Vehicle.class)` threw `NoUniqueBeanDefinitionException` (the same
problem as note 03 §2). **Fix:** the three `@Bean` Vehicle methods were removed, so
the scanned `@Component` is the only `Vehicle`. The old code stays in Git history and in
note 03.

---

## Part B: The bean lifecycle

### 5. What happens to a bean, in order

When the context starts, Spring takes every singleton bean through these steps. I verified
the order with a test class that uses **all** the hooks at once:

```text
1. constructor                    ← Spring calls new Car()
2. setter name=x                  ← properties / dependencies are set
3. @PostConstruct name=x          ← init hook #1 (sees the properties already set)
4. afterPropertiesSet             ← init hook #2 (InitializingBean)
5. @Bean(initMethod)              ← init hook #3
-- context ready, closing --      ← the bean is used by the application here
D1. @PreDestroy                   ← destroy hook #1
D2. DisposableBean.destroy        ← destroy hook #2
D3. @Bean(destroyMethod)          ← destroy hook #3
```

**Why not just put setup code in the constructor?** Inside the constructor, Spring hasn't
injected the dependencies or set the properties yet. Init hooks run **after** that, so
they can safely use them. Typical uses: check the configuration, open a connection, warm
up a cache.

**Destroy hooks** release what init acquired: close connections, stop threads, flush
buffers.

### 6. The three ways to hook in

| Init | Destroy | From | Recommendation |
|------|---------|------|----------------|
| `@PostConstruct` | `@PreDestroy` | Jakarta (`jakarta.annotation`) | ✅ **Preferred.** Standard Java annotations; your class doesn't depend on Spring. |
| `InitializingBean.afterPropertiesSet()` | `DisposableBean.destroy()` | Spring interfaces | Works, but ties your class to Spring. Mostly used inside Spring itself. |
| `@Bean(initMethod = "…")` | `@Bean(destroyMethod = "…")` | Spring config | For classes you can't edit (library classes). |

### 7. In this project: `InitializingBean` and `DisposableBean`

[Vehicle.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/beans/Vehicle.java):

```java
@Component
public class Vehicle implements InitializingBean, DisposableBean {

    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println("Vehicle bean initialized.");
    }

    @Override
    public void destroy() throws Exception {
        System.out.println("Vehicle bean destroyed.");
    }
}
```

- `implements InitializingBean, DisposableBean`: a Java class can implement **several
  interfaces**. Each one is a promise to provide certain methods.
- `@Override` asks the compiler to check that this method really overrides one from the
  interface. A typo like `afterPropertySet` becomes a compile error instead of a method
  that silently never runs.

Run it:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.shahabuddin.Main
```

Output (verified):

```text
Vehicle bean initialized.
Vehicle name from Spring Context is: null
Vehicle bean destroyed.
```

- "initialized" prints **while the context starts**, before `getBean` is even called,
  because singletons are created eagerly (note 02).
- "destroyed" prints when the try-with-resources block **closes** the context.
- The name is `null` because nothing sets it. With `@Component`, Spring calls the no-arg
  constructor, and we no longer call `setName` in a `@Bean` method. The commented-out
  `@PostConstruct` method in `Vehicle.java` is one way to give it a value (see §8).

### 8. 🧪 Learned, not applied: `@PostConstruct` / `@PreDestroy`

These are commented out in `Vehicle.java` right now:

```java
@PostConstruct
public void initialize() {
    this.name = "Audi";
}

@PreDestroy
public void destroy() {          // ⚠️ name clash, see below
    System.out.println("Destroying Vehicle Bean");
}
```

⚠️ **If you uncomment this as is, it won't compile.** `DisposableBean` already requires a
method `public void destroy()`, and a class can't have two methods with the same name and
parameters. To use both mechanisms together, give the `@PreDestroy` method a different
name, e.g. `cleanUp()`. In real code, pick **one** mechanism (preferably the annotations)
rather than both.

#### Why the `jakarta.annotation-api` dependency is needed

`@PostConstruct` and `@PreDestroy` are **not part of Spring or of the JDK**. They used to
ship with Java (`javax.annotation`) but were removed in Java 11, and now live in the
Jakarta EE library. So [pom.xml](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/pom.xml) needs:

```xml
<dependency>
    <groupId>jakarta.annotation</groupId>
    <artifactId>jakarta.annotation-api</artifactId>
    <version>3.0.0</version>
</dependency>
```

⚠️ **Verified trap:** I ran the lifecycle test **without** this jar on the classpath, and
`@PostConstruct` and `@PreDestroy` were **silently skipped**, with no error:

```text
1. constructor
2. setter name=x
4. afterPropertiesSet          ← step 3 (@PostConstruct) is missing
5. @Bean(initMethod)
D2. DisposableBean.destroy     ← D1 (@PreDestroy) is missing
D3. @Bean(destroyMethod)
```

(Spring Boot starters include this library for you.)

### 9. Why closing the context matters even more now

`Main` was briefly changed to call `context.close()` on the last line, without
try-with-resources. While `getBean` was still throwing `NoUniqueBeanDefinitionException`,
the output was:

```text
Vehicle bean initialized.
Vehicle bean initialized.
Vehicle bean initialized.
[ERROR] ... No qualifying bean of type 'com.shahabuddin.beans.Vehicle' available ...
```

There is **no "destroyed" line at all**. The exception jumped past `context.close()`, so no
destroy hooks ran. With try-with-resources and the same exception (verified):

```text
Vehicle bean initialized.   ×3
Vehicle bean destroyed.     ×3
caught NoUniqueBeanDefinitionException
```

✅ `Main` uses try-with-resources again (restored on 2026-11-20):

```java
try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
    var veh = context.getBean(Vehicle.class);
    System.out.println("Vehicle name from Spring Context is: " + veh.getName());
}
```

**Lesson:** cleanup code must run on the *error* path too. That's exactly what
try-with-resources (or `finally`) guarantees.

> Destroy hooks only run for **singleton** beans. For `prototype`-scoped beans Spring
> doesn't track the instances, so it never destroys them (verified in [note 13 §3](13-bean-scopes.md)).

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `@Component` but no `@ComponentScan` covering its package | Bean silently missing → `NoSuchBeanDefinitionException` |
| Scan package typo or wrong package | Same; no warning |
| `@Component` class **and** `@Bean` methods for the same type | Several beans → `NoUniqueBeanDefinitionException` |
| `@PostConstruct` without `jakarta.annotation-api` | Silently ignored |
| `<dependency>` placed outside `<dependencies>` in `pom.xml` | Maven fails: `Malformed POM … Unrecognised tag: 'dependency'` (see note 00) |
| Calling `context.close()` on the last line | Skipped when an exception is thrown; use try-with-resources |
| `@PreDestroy public void destroy()` plus `implements DisposableBean` | Compile error: duplicate method |

## Interview questions

**Q: Difference between `@Component` and `@Bean`?**
`@Component` goes on a class, and Spring finds it through component scanning and creates it.
`@Bean` goes on a method in a `@Configuration` class; you create the object yourself. Use
`@Bean` for third-party classes you can't annotate.

**Q: `@Component` vs `@Service` vs `@Repository`?**
All three are scanned and create beans. `@Service` and `@Repository` state the class's role.
`@Repository` also translates persistence exceptions.

**Q: What is the order of bean initialization callbacks?**
Constructor → dependency/property injection → `@PostConstruct` → `afterPropertiesSet()` →
custom `initMethod`. Destroy: `@PreDestroy` → `destroy()` → custom `destroyMethod`.

**Q: Why not do initialization in the constructor?**
Dependencies injected through setters or fields aren't set yet when the constructor runs.
`@PostConstruct` runs after injection is complete.

**Q: Are destroy callbacks called for prototype beans?**
No. Spring hands prototype instances over and doesn't manage their destruction.
