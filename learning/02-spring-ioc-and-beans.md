# 02 — Spring IoC Container and Beans

## Goal

Understand the core idea of Spring: **you stop creating objects yourself with `new`; Spring
creates them, keeps them, and hands them to you.** Everything else in Spring (Boot, Web, Data,
Security) is built on this.

---

## 1. The problem Spring solves

Without Spring, each class creates the objects it needs:

```java
public class JobService {
    private final JobRepository repo = new JobRepository();   // hard-wired
}
```

Problems:
- `JobService` is **tightly coupled** to one specific `JobRepository`.
- In a test you can't swap in a fake repository.
- When `JobRepository` needs its own dependencies (a database connection, config), every
  class that uses it has to know how to build them.

## 2. Inversion of Control (IoC) and Dependency Injection (DI)

**Inversion of Control** means your code no longer controls *creating* and *connecting*
objects. A container does that, which "inverts" who's in charge.

**Dependency Injection** is how Spring does IoC: an object *declares* what it needs, and
the container *gives* (injects) it:

```java
public class JobService {
    private final JobRepository repo;
    public JobService(JobRepository repo) { this.repo = repo; }  // Spring passes it in
}
```

(We'll use injection in an upcoming step; see the [roadmap](roadmap.md).)

## 3. Key vocabulary

| Term | Meaning |
|------|---------|
| **Spring bean** | Any object that the Spring container creates and manages |
| **IoC container** | The part of Spring that creates, stores, wires and destroys beans |
| **`ApplicationContext`** | The interface for the IoC container you use day to day |
| **Bean definition** | Spring's "recipe" for one bean: its type, name, scope, how to create it |
| **Configuration class** | A class that holds bean recipes (`@Configuration`) |

> `BeanFactory` is the minimal container interface. `ApplicationContext` extends it and adds
> events, internationalisation, resource loading, and more. In practice you always use
> `ApplicationContext`. Full comparison, with verified output, in §11.

---

## 4. In this project, step by step

### Step 1: A plain Java class

[Vehicle.java](../src/main/java/com/shahabuddin/beans/Vehicle.java) is an ordinary class.
It has **no Spring annotations**, and doesn't need any. Any class can become a bean.

### Step 2: Create the object yourself (no Spring)

[Main.java](../src/main/java/com/shahabuddin/Main.java):

```java
Vehicle vehicle = new Vehicle();
vehicle.setName("Audi");
IO.println("Vehicle name from non-spring context is: " + vehicle.getName());
```

This object lives only in this variable. Spring knows nothing about it.

### Step 3: Write the recipes in a configuration class

[ProjectConfig.java](../src/main/java/com/shahabuddin/config/ProjectConfig.java):

> This is the **first version** of the config, with one `Vehicle`. The project now has three
> `Vehicle` beans; see [note 03](03-multiple-beans-and-naming.md).

```java
@Configuration
public class ProjectConfig {

    @Bean
    Vehicle vehicle() {
        var veh = new Vehicle();
        veh.setName("Tesla");
        return veh;
    }

    @Bean
    String hello() { return "Hello World"; }

    @Bean
    Integer luckyNumber() { return 10; }
}
```

- **`@Configuration`** tells Spring: "this class contains bean definitions; process it."
- **`@Bean`** on a method tells Spring: "call this method once, and keep the object it
  returns as a bean."
- The **return type** is the bean's **type** (`Vehicle`, `String`, `Integer`).
- The **method name** is the bean's **name** (`vehicle`, `hello`, `luckyNumber`).

We still use `new` here, but only in **one central place**, and Spring owns the result.

### Step 4: Start the container

```java
try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
    // ... use the context (Step 5) ...
}   // context is closed here (see §9)
```

`AnnotationConfigApplicationContext` is an `ApplicationContext` that reads Java
annotations (as opposed to the old XML config). When `new AnnotationConfigApplicationContext(...)`
runs, Spring:

1. Registers `ProjectConfig` itself as a bean (named `projectConfig`).
2. Reads every `@Bean` method and creates a **bean definition** for it.
3. **Eagerly** creates all singleton beans by calling those methods.
4. Stores the results in the container.

### Step 5: Get beans from the container

```java
var veh = context.getBean(Vehicle.class);                  // by TYPE (only while there was ONE Vehicle bean, see note 03)
String hello = context.getBean(String.class);              // by TYPE
Integer num = context.getBean(Integer.class);              // by TYPE
String hello1 = context.getBean("hello", String.class);    // by NAME + TYPE (no cast)
```

> **History:** the first version used `(String) context.getBean("hello")`. That lookup
> by name alone returns `Object` and needs a cast. Changed on 2026-11-19; see §5 for why.

### Output of this first version (verified by running it)

```text
Hello and welcome!
Vehicle name from non-spring context is: Audi
Vehicle name from Spring Context is: Tesla
String value  from Spring Context is: Hello World
Integer value  from Spring Context is: 10
String value  from Spring Context is: Hello World
```

"Audi" came from our own `new`; "Tesla" came from Spring. These are **two different
objects**.

---

## 5. The ways to call `getBean`

| Call | Returns | When to use |
|------|---------|-------------|
| `getBean(Vehicle.class)` | `Vehicle` | Only one bean of that type exists ✅ most common |
| `getBean("hello")` | `Object` (you must cast) | Rarely; the cast can fail at runtime |
| `getBean("hello", String.class)` | `String` | Several beans share a type; pick one by name, with no cast ✅ (used in `Main`) |

Why name + type beats name + cast:

- **No cast**, so the compiler checks the type for you.
- **Fails early, with a clear message.** If the bean `hello` isn't a `String`, Spring throws
  `BeanNotOfRequiredTypeException` at the lookup, naming the bean and both types
  (verified in §8). A cast would instead throw a `ClassCastException`, which is less clear.

---

## 6. Bean names: what Spring actually registered

I listed every bean name with `context.getBeanDefinitionNames()` (first version; the
current list is in [note 03 §4](03-multiple-beans-and-naming.md)):

```text
org.springframework.context.annotation.internalConfigurationAnnotationProcessor
org.springframework.context.annotation.internalAutowiredAnnotationProcessor
org.springframework.context.event.internalEventListenerProcessor
org.springframework.context.event.internalEventListenerFactory
projectConfig
vehicle
hello
luckyNumber
```

What this shows:
- The first four are **Spring's own internal beans**, the machinery that processes
  annotations like `@Configuration`, `@Bean` and `@Autowired`.
- `projectConfig`: the configuration class is a bean too. Its name is the class name with a
  lowercase first letter.
- `vehicle`, `hello`: named after their `@Bean` methods.
- `luckyNumber`: also named after its method.

### ✅ Fixed (2026-11-19): `LuckyNumber` → `luckyNumber`

The method was first written as `LuckyNumber()` (capital L). The bean name is copied
**exactly** from the method name, so the bean was registered as `LuckyNumber`, and
`getBean("luckyNumber")` threw `NoSuchBeanDefinitionException` (verified). Renaming the
method to `luckyNumber()` follows Java naming conventions, and now
`getBean("luckyNumber")` returns `10` (verified).

**Lesson:** a `@Bean` method name isn't just style. It's the bean's public name, and other
code (and `@Qualifier`, later) will refer to it.

You can give a bean an explicit name: `@Bean(name = "myCar")` or `@Bean("myCar")`.
✅ Now applied; see [note 03 §3](03-multiple-beans-and-naming.md).

---

## 7. Singleton: one shared object

By default every bean is a **singleton**: Spring creates it **once** and returns the
**same object** every time.

Verified:

```java
ctx.getBean(Vehicle.class) == ctx.getBean(Vehicle.class)   // true
```

`==` on objects checks "is this the exact same object in memory?", and the answer is yes.

> Don't confuse this with the Gang-of-Four Singleton *pattern* (one instance per JVM).
> A Spring singleton is one instance **per container, per bean definition**. Two `@Bean`
> methods returning `Vehicle` give two separate singleton beans.

All six scopes (`prototype`, `request`, `session`, …), verified: [note 13](13-bean-scopes.md).

---

## 8. 🧪 Learned, not applied: what goes wrong (all verified)

I tested these in a scratch program so I know what each error means.

| Situation | Exception |
|-----------|-----------|
| `getBean(Double.class)`, but no bean of that type exists | `NoSuchBeanDefinitionException` |
| `getBean("luckyNumber")` when the method was `LuckyNumber()` (wrong case) | `NoSuchBeanDefinitionException` |
| `getBean("hello", Integer.class)`, name exists but the type is wrong | `BeanNotOfRequiredTypeException` |
| Two `@Bean` methods return `Vehicle`, then `getBean(Vehicle.class)` | `NoUniqueBeanDefinitionException` |

### Fixing "two beans of the same type"

```java
@Configuration
class TwoVehicles {
    @Bean Vehicle v1() { ... }   // "A"
    @Bean Vehicle v2() { ... }   // "B"
}

ctx.getBean(Vehicle.class);              // ❌ NoUniqueBeanDefinitionException
ctx.getBean("v2", Vehicle.class);        // ✅ returns "B"
```

Spring can't guess which one you mean. The options:

1. **Ask by name**: `getBean("v2", Vehicle.class)` (verified ✅; now applied in the
   project, see [note 03](03-multiple-beans-and-naming.md)).
2. **`@Primary`** on one `@Bean` method, which makes it the default (🔜 next step).
3. **`@Qualifier("v2")`** at the injection point (🔜 when we learn injection).

---

## 9. Closing the context

✅ **Applied (2026-11-19).**

`ApplicationContext` holds resources (and later, database connections and thread pools).
Close it when you're done, so Spring can:

1. Run every bean's cleanup ("destroy") callbacks, e.g. `DisposableBean.destroy()` or
   methods marked `@PreDestroy` (see [note 04](04-component-scan-and-bean-lifecycle.md)).
2. Destroy the singletons and release what they hold.
3. Mark the context inactive, so no more beans can be fetched from it.

The first version of `Main` never closed it. It now uses **try-with-resources** (Java
syntax explained in note 01 §10):

```java
try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
    var veh = context.getBean("vehicle1", Vehicle.class);
    IO.println(veh.getName());
}   // context.close() is called automatically here, even if an exception happens
```

This works because `AnnotationConfigApplicationContext` implements `AutoCloseable`.

**Verified** with `context.isActive()`:

```text
active inside try? true
active after try?  false
```

Why it didn't *visibly* break before: the program ended right away, and the JVM exiting
frees memory anyway. But destroy callbacks never ran. Once beans hold real resources
(database pools, open files, background threads), that becomes a real leak. Build the
habit now.

(Spring Boot closes the context for you on shutdown, so you'll rarely write this later.)

---

## 10. Spring Framework vs Spring Boot

| | Spring Framework (what we use now) | Spring Boot (later) |
|-|------------------------------------|---------------------|
| Dependency | `spring-context` | `spring-boot-starter-*` |
| Starting the container | `new AnnotationConfigApplicationContext(...)` | `SpringApplication.run(...)` |
| Configuration | Written by you | Auto-configured, with sensible defaults |
| Version in this project | Spring Framework **7.0.10** | Spring Boot 4.x is built on Framework 7 |

Learning the plain Framework first makes Boot's "magic" understandable: Boot just creates
an `ApplicationContext` and registers a lot of `@Bean`s for you.

---

## 11. 🧪 `BeanFactory` vs `ApplicationContext` in depth

Spring has **two levels** of IoC container:

| | `BeanFactory` | `ApplicationContext` |
|-|---------------|----------------------|
| What it is | The **basic** container: creates, wires and stores beans | The **full** container: everything `BeanFactory` does, plus enterprise features |
| Package | `org.springframework.beans.factory` (jar `spring-beans`) | `org.springframework.context` (jar `spring-context`) |
| Main implementation | `DefaultListableBeanFactory` | `AnnotationConfigApplicationContext` (ours); Spring Boot creates one for you |
| Singleton creation | **Lazy**: created on the first `getBean` | **Eager**: all created at startup |
| `@PostConstruct`, `@Autowired`, `@Configuration` processing | ❌ Not automatically; you must register post-processors yourself | ✅ Built in |
| Events (`ContextRefreshedEvent`, `@EventListener`) | ❌ | ✅ |
| Messages / i18n (`MessageSource`) | ❌ | ✅ |
| Environment, properties, profiles | ❌ | ✅ `getEnvironment()` |
| Loading files (`classpath:…`) | ❌ | ✅ `ResourcePatternResolver` |
| When to use | Almost never directly (very memory-constrained cases, framework internals) | ✅ **Always** in applications |

### How they relate

`ApplicationContext` **is a** `BeanFactory` (it extends the interface), and internally it
**has a** `DefaultListableBeanFactory` that does the actual bean work. It wraps the basic
factory and adds the extra features on top.

```text
                  BeanFactory
                       ▲
          ┌────────────┴────────────┐
 ListableBeanFactory      HierarchicalBeanFactory
          ▲                         ▲
          └────────────┬────────────┘
                ApplicationContext ──also extends──▶ EnvironmentCapable, MessageSource,
                       ▲                             ApplicationEventPublisher,
                       │                             ResourcePatternResolver
     AnnotationConfigApplicationContext
         └── holds internally → DefaultListableBeanFactory
```

Verified with reflection:

```text
ctx instanceof BeanFactory? true
ctx.getBeanFactory() class: DefaultListableBeanFactory
ApplicationContext extends: [EnvironmentCapable, ListableBeanFactory, HierarchicalBeanFactory,
                             MessageSource, ApplicationEventPublisher, ResourcePatternResolver]
```

### The same bean in both containers (verified)

A test class `Car` with a printing constructor, `@PostConstruct` and `afterPropertiesSet()`:

**1. Plain `BeanFactory`:**

```java
var bf = new DefaultListableBeanFactory();
bf.registerBeanDefinition("car", new RootBeanDefinition(Car.class));
bf.getBean("car");
```

```text
   registered; nothing created yet
   calling getBean...
   Car constructor           ← created only now (LAZY)
   afterPropertiesSet        ← InitializingBean works (it's part of spring-beans)
                             ← @PostConstruct did NOT run
```

**2. `ApplicationContext`:**

```java
try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
    ctx.getBean(Car.class);
}
```

```text
   creating context...
   Car constructor           ← created during startup (EAGER)
   @PostConstruct            ← annotation processing is built in
   afterPropertiesSet
   event: ContextRefreshedEvent   ← events: "the context is ready"
   context created; calling getBean...
   closing...
   event: ContextClosedEvent
```

What this shows:

1. **Lazy vs eager.** The `BeanFactory` waited for `getBean`. The context built everything
   up front.
2. **No annotation magic in a plain `BeanFactory`.** `@PostConstruct` was silently
   ignored. That's also why our `@Configuration` + `@Bean` setup needs an
   `ApplicationContext`: the code that reads those annotations is registered by the
   context (they are the `internal…Processor` beans listed in §6).
3. **Events.** Only the context published `ContextRefreshedEvent` / `ContextClosedEvent`.

### Why eager creation is better (for applications)

If a bean is misconfigured, an `ApplicationContext` fails **at startup**, when you're
watching. A lazy `BeanFactory` would fail later, maybe hours afterwards, on the first request
that needs that bean. Eager startup = **fail fast**. You can still make individual beans
lazy with `@Lazy`; see [note 14](14-eager-and-lazy-instantiation.md).

---

## Common mistakes

| Mistake | What happens |
|---------|--------------|
| Forgetting `@Configuration` and passing the class anyway | It still mostly works ("lite mode"), but calls *between* `@Bean` methods create new objects instead of returning the singleton (verified in [note 08 §1](08-bean-wiring.md)). Always use `@Configuration`. |
| Forgetting `@Bean` on a method | That object is never registered → `NoSuchBeanDefinitionException` |
| Capitalised method name | Bean name is capitalised too (`LuckyNumber`). I made this mistake and fixed it; see §6. |
| `(Type) getBean("name")` | A cast that fails late with `ClassCastException`; use `getBean("name", Type.class)` |
| Never closing the context | Destroy callbacks never run; resources may leak |
| Changing the object you got from `new` and expecting Spring's copy to change | They are different objects |

---

## Interview questions

**Q: What is IoC?**
A design principle where a framework, not your code, controls creating and wiring objects.

**Q: What is DI?**
The technique Spring uses to implement IoC: dependencies are supplied to an object (through
a constructor, setter or field) instead of the object creating them.

**Q: What is a Spring bean?**
An object that is instantiated, assembled and managed by the Spring IoC container.

**Q: `BeanFactory` vs `ApplicationContext`?**
`ApplicationContext` is a superset of `BeanFactory`. It adds eager singleton creation, events,
i18n and resource loading. Use `ApplicationContext`.

**Q: Is `ApplicationContext` a `BeanFactory`?**
Yes, in two ways. It *extends* the `BeanFactory` interface, and internally it *holds* a
`DefaultListableBeanFactory` that does the actual bean work (§11).

**Q: Does a plain `BeanFactory` process `@PostConstruct` or `@Autowired`?**
No. Those annotations are handled by bean post-processors, which an `ApplicationContext`
registers automatically and a plain `BeanFactory` doesn't. Verified: `@PostConstruct` was
silently skipped.

**Q: Why are singletons created eagerly in an `ApplicationContext`?**
To fail fast. Configuration errors show up at startup, not on the first request that
needs the bean.

**Q: What is the default bean scope?**
Singleton: one shared instance per container.

**Q: What is the default name of a `@Bean`?**
The method name.

**Q: Two beans of the same type, and you request by type. What happens?**
`NoUniqueBeanDefinitionException`. Resolve it with a bean name, `@Primary`, or `@Qualifier`.
