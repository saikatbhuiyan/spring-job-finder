# 14 — Eager & Lazy Instantiation

## Goal

Understand **when** Spring creates a bean: at startup (**eager**) or on first use
(**lazy**). Learn how to switch between them, why eager is the default, and the traps
when you mix the two. Builds on [note 02 §11](02-spring-ioc-and-beans.md) and
[note 13](13-bean-scopes.md). All results verified on Spring 7.0.10.

Status: eager ✅ (every bean in the project is created eagerly); `@Lazy` 🧪.

---

## 1. The two strategies

| | **Eager** (default for singletons) | **Lazy** |
|-|------------------------------------|----------|
| Created | During `refresh()` / startup | On the first `getBean` or first use |
| Startup time | Slower: everything is built up front | Faster |
| First request | Fast: everything is ready | Slower: may build beans on demand |
| Config errors found | **At startup** ✅ (fail fast) | **Later, at runtime** ❌ |
| Memory | All beans in memory | Unused beans never created |
| How | Nothing to do | `@Lazy` |

> **Prototype beans are always lazy.** They're created only when requested (note 13 §3).
> Eager vs lazy is about **singletons**.

---

## 2. Eager by default (verified)

```java
@Component        class EagerBean { EagerBean() { log("EagerBean constructor"); } }
@Component @Lazy  class LazyBean  { LazyBean()  { log("LazyBean constructor"); } }
```

```text
EagerBean constructor          ← during startup
--- context ready ---
LazyBean constructor           ← only on the first getBean
getBean again...               ← nothing printed: still a singleton, created only once
```

`@Lazy` changes **when** the singleton is created, not **how many**. It's still one shared
instance.

---

## 3. Where `@Lazy` can go

| Placement | Effect |
|-----------|--------|
| `@Component @Lazy class X` | That component is lazy |
| `@Bean @Lazy X x()` | That `@Bean` is lazy |
| `@Configuration @Lazy class Cfg` | **All** `@Bean` methods in it are lazy |
| `@Lazy(false)` on one bean | Overrides a class-level `@Lazy`: back to eager |
| `Foo(@Lazy Bar bar)` (injection point) | Injects a **proxy**; the real `Bar` is created on first method call (§5) |

Class-level `@Lazy` with an override, verified:

```java
@Configuration @Lazy
class AllLazy {
    @Bean String a() { ... }                       // lazy (inherits from the class)
    @Bean @Lazy(false) Integer b() { ... }          // eager (override)
}
```

```text
bean b created (@Lazy(false) override)     ← startup
--- context ready ---
bean a created                             ← on getBean("a")
```

---

## 4. ⚠️ Trap: an eager bean that needs a lazy bean (verified)

```java
@Component @Lazy class LazyBean { ... }
@Component class NeedsLazy { NeedsLazy(LazyBean b) { ... } }    // eager, needs LazyBean
```

```text
LazyBean constructor                       ← created at STARTUP anyway!
NeedsLazy constructor, got LazyBean
--- context ready ---
```

To build the eager `NeedsLazy`, Spring **must** have a `LazyBean` right now, so `@Lazy` is
silently ignored. A bean is only really lazy if **nothing eager depends on it**.

---

## 5. Fix: `@Lazy` at the injection point (verified)

```java
@Component class NeedsLazyViaProxy {
    NeedsLazyViaProxy(@Lazy LazyBean b) { ... }
}
```

```text
NeedsLazyViaProxy constructor, got Exp18$LazyBean$$SpringCGLIB$$0    ← a PROXY
--- context ready ---
LazyBean constructor                       ← real bean created on first method call
LazyBean.hi()
```

Spring injects a **proxy** that creates the real bean the first time a method is called. The
same mechanism breaks circular dependencies (note 10 §4), and the same trap applies: **only
method calls** go through the proxy, not field access.

---

## 6. ⚠️ The big trade-off: when do errors show up? (verified)

A bean whose constructor fails, e.g. missing configuration:

**Eager:**

```text
startup FAILS: IllegalStateException: bad config: missing API key
```

The app **refuses to start**. You see the problem immediately, during deployment.

**Lazy:**

```text
--- context ready --- (app looks healthy)
first user request needs it...
runtime FAILS: IllegalStateException: bad config: missing API key
```

The app **starts and looks healthy**. Health checks pass, the deployment "succeeds", and then
the first **real user** who triggers that bean gets the error, possibly hours later.

This is why Spring creates singletons **eagerly by default**: **fail fast**.

---

## 7. Making everything lazy (verified)

Spring Boot has a switch, `spring.main.lazy-initialization=true`, which marks every bean
definition as lazy. In plain Spring, the same effect comes from a `BeanFactoryPostProcessor`
(code that edits bean definitions before any bean is created):

```java
@Bean
static BeanFactoryPostProcessor makeEverythingLazy() {
    return bf -> {
        for (String name : bf.getBeanDefinitionNames())
            bf.getBeanDefinition(name).setLazyInit(true);
    };
}
```

```text
--- context ready ---          ← nothing created at startup
EagerBean constructor          ← even "EagerBean" waits for getBean
```

Global lazy init is sometimes used **in development** for faster restarts. It's risky in
production because of §6.

---

## 8. When to use lazy

| Use `@Lazy` for… | Example |
|------------------|---------|
| Heavy beans that are rarely needed | A PDF report generator used once a month |
| Optional features | An integration with an external service that's often disabled |
| Faster startup in development or tests | Global lazy init, **dev only** |
| Breaking a cycle (temporary) | `@Lazy` at an injection point (note 10) |

| Keep eager (the default) for… | Why |
|-------------------------------|-----|
| Almost everything | Fail fast, predictable first-request latency |
| Beans that validate configuration | Errors must stop the deployment |
| Beans with startup work (caches, schedulers, listeners) | Lazy ones might never start at all |

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `@Lazy` on a bean that an eager bean injects | Created at startup anyway (verified) |
| Global lazy init in production | Config errors appear only when users hit them (verified) |
| Expecting `@Lazy` to create several instances | It's still a singleton; use prototype scope for that |
| Accessing fields through a `@Lazy` injection proxy | `null`; only method calls are forwarded |
| A lazy bean that should start background work (scheduler, listener) | It never starts if nobody asks for it |

## Interview questions

**Q: Are Spring beans created eagerly or lazily by default?**
Singletons are created eagerly at startup. Prototypes are always created on demand.

**Q: How do you make a bean lazy?**
`@Lazy` on the `@Component` class or `@Bean` method, or on a `@Configuration` class for all its
beans.

**Q: A `@Lazy` bean is injected into a normal singleton. When is it created?**
At startup, because the eager bean needs it. Add `@Lazy` at the injection point to get a proxy
instead.

**Q: Why is eager the default?**
To fail fast: configuration errors stop the app at startup instead of surfacing later on a user
request.

**Q: Downsides of lazy initialization?**
Errors are discovered late, the first request is slower, and beans that should start background
work may never be created.

**Q: How do you make all beans lazy in Spring Boot?**
`spring.main.lazy-initialization=true`, typically only in development.
