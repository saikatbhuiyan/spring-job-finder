# 12 — Programmatic Bean Registration in Spring

## Goal

So far beans were declared **statically**, with `@Bean` methods or `@Component` classes, so the
set of beans is fixed when you write the code. Sometimes you need to register beans **from
code at runtime**:

- the **number** of beans depends on configuration (one client per job board listed in a
  property);
- a bean should exist **only if** some condition holds;
- you're writing a **library/framework** that registers beans for its users;
- the object already exists and you just want Spring to hand it out.

This note covers every way to do that, including **`BeanRegistrar`**, which is new in
Spring Framework 7. All results verified on Spring 7.0.10.

Status: 🧪 learned and verified; not yet in the project code.

---

## 1. The options at a glance

| Way | Where it runs | Lifecycle callbacks (`@PostConstruct`, destroy) | Best for |
|-----|---------------|--------------------------------------------------|----------|
| `context.registerBean(...)` | On the context object, usually **before** `refresh()` | ✅ yes | Small apps, tests, bootstrapping in `main` |
| `beanFactory.registerSingleton(name, obj)` | Any time | ❌ **none** | Handing an already-built object to Spring |
| **`BeanRegistrar`** (Spring 7) ✅ | Imported from a `@Configuration` (`@Import`) | ✅ yes | **Config-driven / conditional registration** (recommended) |
| `BeanDefinitionRegistryPostProcessor` | Startup, before beans are created | ✅ yes | Pre-Spring-7 code and low-level framework work |
| `ImportBeanDefinitionRegistrar` | Via `@Import` / `@Enable…` | ✅ yes | Framework `@EnableXxx` annotations (note 06 §6) |

---

## 2. `registerBean` on the context

`AnnotationConfigApplicationContext` (a `GenericApplicationContext`) has `registerBean`
methods. The usual pattern: create an **empty** context, register, then `refresh()`.

```java
var ctx = new AnnotationConfigApplicationContext();        // no config class yet

ctx.registerBean(Vehicle.class);                                       // 1. class only
ctx.registerBean("tesla", Vehicle.class, () -> new Vehicle("Tesla"));  // 2. name + Supplier
ctx.registerBean("bmw", Vehicle.class, () -> new Vehicle("BMW"),       // 3. + customizer
                 bd -> { bd.setPrimary(true); bd.setLazyInit(true); });

ctx.refresh();                                              // now the container starts
```

Verified output:

```text
refreshing...
new Vehicle(default)    @PostConstruct default      ← created at refresh (eager)
new Vehicle(Tesla)      @PostConstruct Tesla
names: [exp16.Vehicle, tesla, bmw]                  ← class-only → name from the class
new Vehicle(BMW)        @PostConstruct BMW          ← only now: it was lazy
getBean(Vehicle.class) = BMW (primary)
```

What each form does:

1. **Class only.** Spring calls the constructor (with autowiring, like a `@Component`). The
   name follows the class-name rule (note 03 §9).
2. **`Supplier`** (`() -> new Vehicle("Tesla")`) means you control construction, like the body of a
   `@Bean` method. No reflection needed.
3. **Customizer** (`bd -> ...`) tweaks the bean definition: `setPrimary`, `setLazyInit`,
   `setScope("prototype")`, `setInitMethodName`, `setDescription`, …

### After `refresh()`? (verified)

```java
ctx.registerBean("late", Vehicle.class, () -> new Vehicle("Late"));   // context already running
ctx.getBean("late");   // → new Vehicle(Late), @PostConstruct Late
```

It worked in Spring 7.0.10, but the bean was **not** created eagerly (only on `getBean`), and
other beans that were already wired can't receive it. **Register before `refresh()`**, which is
the supported, predictable approach.

---

## 3. `registerSingleton`: hand over an existing object

```java
var mine = new Vehicle("Prebuilt");
ctx.getBeanFactory().registerSingleton("prebuilt", mine);
ctx.getBean("prebuilt") == mine;      // true
```

Verified, with a caveat. In the output there's **no** `@PostConstruct Prebuilt`, and on
`close()` **no** `destroy Prebuilt`:

```text
new Vehicle(Prebuilt)
same object? true
closing context 1...
destroy Late
destroy BMW
destroy Tesla
destroy default          ← "Prebuilt" is missing: Spring doesn't manage its lifecycle
```

Spring treats it as **already fully built and owned by you**: no injection, no init callbacks,
no destroy callbacks. **You** are responsible for cleaning it up. Use it for objects created
outside Spring (e.g. by a legacy library) that you just want to make injectable.

---

## 4. ✅ `BeanRegistrar`: the Spring 7 way

Spring Framework 7 added a dedicated, clean API for programmatic registration:

```java
public interface BeanRegistrar {
    void register(BeanRegistry registry, Environment env);
}
```

You get:
- a **`BeanRegistry`** with `registerBean(...)` and `registerAlias(...)`;
- the **`Environment`**, so you can read properties and profiles to decide *what* to register.

### Example: one client per job board, from a property

```java
public class JobBoardRegistrar implements BeanRegistrar {

    @Override
    public void register(BeanRegistry registry, Environment env) {
        String[] boards = env.getProperty("jobfinder.boards", "linkedin").split(",");

        for (String board : boards) {                                   // a loop: N beans
            registry.registerBean(board + "Client", JobBoardClient.class, spec -> spec
                .description("Client for " + board)
                .supplier(ctx -> new JobBoardClient(board, ctx.bean("vehicle", Vehicle.class))));
        }

        if (env.containsProperty("jobfinder.demo")) {                   // conditional bean
            registry.registerBean("demoVehicle", Vehicle.class, spec -> spec
                .lazyInit()
                .supplier(ctx -> new Vehicle("Demo (lazy)")));
        }
    }
}

@Configuration
@Import(JobBoardRegistrar.class)              // ← how a registrar is activated
class RegCfg {
    @Bean Vehicle vehicle() { return new Vehicle("Main"); }
}
```

Verified with `jobfinder.boards=linkedin,indeed,glassdoor` and `jobfinder.demo` set:

```text
clients: {linkedinClient=Client(linkedin), indeedClient=Client(indeed), glassdoorClient=Client(glassdoor)}
description: Client for indeed
demoVehicle created yet? false            ← lazyInit()
new Vehicle(Demo (lazy))                  ← created on first getBean
destroy Demo (lazy)                       ← full lifecycle, unlike registerSingleton
```

Verified with `jobfinder.boards=indeed` and **no** `jobfinder.demo`:

```text
beans: [exp16.RegCfg, vehicle, indeedClient, ...]     ← one client, no demoVehicle
```

**The same code produced different beans from configuration alone.** That's the point of
programmatic registration.

### The `Spec` options (from the Spring 7 API)

| `spec.` | Same as |
|---------|---------|
| `supplier(ctx -> ...)` | The body of a `@Bean` method; `ctx.bean(...)` / `ctx.beanProvider(...)` fetch dependencies |
| `primary()` | `@Primary` |
| `fallback()` | `@Fallback` |
| `lazyInit()` | `@Lazy` |
| `prototype()` / `scope("…")` | `@Scope` |
| `order(n)` | `@Order` |
| `description("…")` | `@Description` |
| `notAutowirable()` | Bean exists but isn't a candidate for autowiring |

### ⚠️ Lesson from a failed first run

The first version of the registrar used `ctx.bean(Vehicle.class)`. Because the registrar
**also** registered `demoVehicle`, there were two `Vehicle` beans, and startup failed:

```text
BeanCreationException: Error creating bean with name 'linkedinClient': Instantiation of supplied bean failed
Caused by: NoUniqueBeanDefinitionException: ... expected single matching bean but found 2: vehicle,demoVehicle
```

Programmatically registered beans are **ordinary beans**. They take part in autowiring and
can create the same ambiguity as any other bean (note 03 §8). The fix was a name lookup:
`ctx.bean("vehicle", Vehicle.class)`.

---

## 5. `BeanDefinitionRegistryPostProcessor`: the classic way

Before Spring 7, the standard hook was a post-processor that runs at startup, **before** any
normal bean is created:

```java
public class LegacyRegistrar implements BeanDefinitionRegistryPostProcessor {
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        var bd = new RootBeanDefinition(Vehicle.class, () -> new Vehicle("FromPostProcessor"));
        bd.setPrimary(true);
        registry.registerBeanDefinition("ppVehicle", bd);
    }
}

@Configuration
class PpCfg {
    @Bean static LegacyRegistrar legacy() { return new LegacyRegistrar(); }   // ← static!
    @Bean Vehicle other() { return new Vehicle("Other"); }
}
```

Verified: `getBean(Vehicle.class) = FromPostProcessor` (it was registered as primary).

- The `@Bean` method must be **`static`**. Post-processors are needed *before* the config
  class itself is ready, and a static method lets Spring call it without creating
  `PpCfg` first.
- It works with raw `BeanDefinition` objects. That's more powerful but more verbose than
  `BeanRegistrar`. You'll see it in older code and in frameworks.

---

## 6. Which one to use

| Situation | Use |
|-----------|-----|
| Normal application beans | **Don't** register programmatically; use `@Component`/`@Bean` |
| Number or presence of beans depends on configuration | **`BeanRegistrar`** (Spring 7) ✅ |
| A quick test or a tiny `main` without config classes | `ctx.registerBean(...)` before `refresh()` |
| An object built elsewhere that must be injectable | `registerSingleton` (and clean it up yourself) |
| Maintaining pre-Spring-7 code / writing a framework | `BeanDefinitionRegistryPostProcessor` / `ImportBeanDefinitionRegistrar` |

Programmatic registration is powerful but **harder to read**: you can't see the beans by
looking at annotations. Reach for it only when the static options can't express what you need.

**Job Finder use (🔜):** a `JobBoardRegistrar` that creates one `JobBoardClient` per board listed in
`application.properties`, so adding a new job board becomes a config change, not a code change.

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `registerSingleton` an object that needs cleanup | Destroy callbacks never run (verified) |
| Registering after `refresh()` and expecting other beans to receive it | They were already wired; the late bean is only available via `getBean` |
| Registering a second bean of a type that others autowire by type | `NoUniqueBeanDefinitionException` (verified) |
| Non-`static` `@Bean` for a `BeanDefinitionRegistryPostProcessor` | The config class is created too early; avoid it |
| Using programmatic registration for ordinary beans | Hides the app's structure; use annotations |

## Interview questions

**Q: How do you register a bean programmatically in Spring?**
`GenericApplicationContext.registerBean(...)`, `registerSingleton(...)`, a `BeanRegistrar`
(Spring 7), a `BeanDefinitionRegistryPostProcessor`, or an `ImportBeanDefinitionRegistrar`.

**Q: What is `BeanRegistrar`?**
A Spring Framework 7 interface, `register(BeanRegistry, Environment)`, activated with `@Import`,
for registering beans programmatically, including in loops or conditionally based on the
environment.

**Q: `registerBean` vs `registerSingleton`?**
`registerBean` registers a *definition*: Spring creates the object and runs the full lifecycle.
`registerSingleton` registers an *existing object*: no injection, no init or destroy callbacks.

**Q: Why must a `BeanDefinitionRegistryPostProcessor` `@Bean` method be static?**
It has to run before regular beans (including its own configuration class) are created.

**Q: When should you register beans programmatically?**
When the set of beans depends on runtime configuration, or when writing framework code.
Otherwise prefer `@Component`/`@Bean`.
