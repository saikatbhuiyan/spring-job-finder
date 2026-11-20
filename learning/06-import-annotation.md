# 06 — The `@Import` Annotation

## Goal

As an app grows, one giant `ProjectConfig` becomes hard to manage. `@Import` lets you
**split configuration into several focused classes and combine them**, without component
scanning. It's also the hidden mechanism behind Spring's `@Enable…` annotations and
Spring Boot's auto-configuration.

Status: 🧪 **learned and verified** in a scratch program; not yet in the project code.

---

## 1. The problem

```java
@Configuration
public class EngineConfig {
    @Bean Engine engine() { return new Engine(); }
}

@Configuration
public class CarConfig {
    @Bean Car car(Engine engine) { return new Car(engine); }   // needs an Engine
}

new AnnotationConfigApplicationContext(CarConfig.class);
```

Spring only reads the configuration classes you give it. `EngineConfig` was never registered,
so there's no `Engine`. Verified:

```text
NoSuchBeanDefinitionException: No qualifying bean of type 'imp.Engine' available:
expected at least 1 bean which qualifies as autowire candidate.
```

## 2. The fix: `@Import`

```java
@Configuration
@Import(EngineConfig.class)          // "also load everything from EngineConfig"
public class CarConfig {
    @Bean Car car(Engine engine) { return new Car(engine); }
}

new AnnotationConfigApplicationContext(CarConfig.class);   // only ONE class passed in
```

Verified:

```text
Car with Engine
beans: [exp10.CarConfig, imp.Exp10$EngineConfig, engine, car]
```

What happened:

1. Spring read `CarConfig`, saw `@Import(EngineConfig.class)`, and processed `EngineConfig` as well.
2. The `engine` bean from `EngineConfig` was **injected into a `@Bean` method of another
   config class** (`car(Engine engine)`). Beans from imported configs are shared across the
   whole context.
3. `CarConfig` becomes the single entry point that pulls in the rest.

---

## 3. What can you import?

`@Import` accepts four kinds of classes. Each one was verified:

| You import… | What Spring does | Example |
|-------------|------------------|---------|
| **A `@Configuration` class** | Processes all its `@Bean` methods (and its own `@Import`s) | `@Import(EngineConfig.class)` |
| **A plain class**: no annotations at all | Registers it as a bean, calling its constructor | `@Import(PlainClock.class)` |
| **An `ImportSelector`** | Calls your code, which **returns class names to import**, decided at runtime | See §5 |
| **An `ImportBeanDefinitionRegistrar`** | Calls your code to register bean definitions by hand | Used by frameworks |
| **A `BeanRegistrar`** (Spring 7) | Calls `register(BeanRegistry, Environment)` to register beans in code | See [note 12 §4](12-programmatic-bean-registration.md) |

Several at once:

```java
@Import({EngineConfig.class, PlainClock.class})
```

```text
beans: [exp10.Multi, imp.Exp10$EngineConfig, engine, imp.PlainClock]
PlainClock (no annotations at all)
```

### ⚠️ Naming: imported classes get their **fully-qualified** name

Look at the names above. The imported classes are registered as `imp.PlainClock` and
`imp.Exp10$EngineConfig`, **not** `plainClock`. So `getBean("plainClock")` would fail.
Look imported classes up **by type** (`getBean(PlainClock.class)`), which works fine.
(`@Bean` methods inside an imported config still use their method names, e.g. `engine`.)

---

## 4. Edge cases (verified)

### Importing the same class twice is harmless

```java
@Import({EngineConfig.class, EngineConfig.class})
```

```text
Engine beans: [engine]       ← processed once, no duplicate
```

This matters when two configs both import a shared `CommonConfig`: you still get one copy.

### Circular imports fail at startup

```java
@Configuration @Import(B.class) class A { ... }
@Configuration @Import(A.class) class B { ... }
```

```text
BeanDefinitionParsingException: Configuration problem: A circular @Import has been detected:
Illegal attempt by @Configuration class 'Exp10.B' to import class 'Exp10.A' as 'Exp10.A'
is already present in the current import stack [Exp10.B->Exp10.A]
```

Fix: arrange configs as a **tree**. A top-level config imports the others, and they don't
import each other back.

---

## 5. `ImportSelector`: choosing imports at runtime

```java
public class MySelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        boolean fast = Boolean.getBoolean("fast");        // read a system property
        return fast ? new String[]{ EngineConfig.class.getName() }
                    : new String[]{ PlainClock.class.getName() };
    }
}

@Configuration @Import(MySelector.class) class Sel {}
```

Verified. The **same** config produced different beans depending on a setting:

```text
ImportSelector runs, fast=false
beans: [exp10.Sel, imp.PlainClock]

ImportSelector runs, fast=true
beans: [exp10.Sel, imp.Exp10$EngineConfig, engine]
```

This is the core idea of **Spring Boot auto-configuration**: a selector looks at what's
on the classpath and in your settings, and imports matching configuration classes.

---

## 6. `@Enable…` annotations are just `@Import` in disguise

I checked Spring's own annotations by reflection:

```text
@EnableScheduling       -> @Import([SchedulingConfiguration])        ← a @Configuration class
@EnableAsync            -> @Import([AsyncConfigurationSelector])     ← an ImportSelector
@EnableAspectJAutoProxy -> @Import([AspectJAutoProxyRegistrar])      ← an ImportBeanDefinitionRegistrar
```

So writing `@EnableScheduling` on your config simply **imports Spring's scheduling
configuration**. All three kinds of import from §3 are used by Spring itself. Spring Boot's
`@SpringBootApplication` → `@EnableAutoConfiguration` → `@Import(AutoConfigurationImportSelector.class)`
works the same way.

You can write your own: an annotation `@EnableJobAlerts` meta-annotated with
`@Import(JobAlertsConfig.class)` would turn a feature on with one line.

---

## 7. `@Import` vs the alternatives

All three of these load several configuration sources. Verified that **A** and **B** give the
same result:

| Option | Code | When to use |
|--------|------|-------------|
| **A. `@Import`** | `@Import(EngineConfig.class)` on the main config | ✅ Explicit list of configs; one entry point; libraries and `@Enable…` features |
| **B. Pass several classes** | `new AnnotationConfigApplicationContext(CarConfig.class, EngineConfig.class)` | Quick tests; but every caller must remember the full list |
| **C. `@ComponentScan`** | `@ComponentScan("com.shahabuddin")` | Your own `@Component`/`@Configuration` classes in your packages (finds them automatically) |

**`@Import` vs `@ComponentScan`:** a scan is *implicit*: anything annotated in the package is
picked up. `@Import` is *explicit*: exactly the listed classes, wherever they live, and they
don't even need annotations. Use scanning for your own code, and `@Import` for configs from
other modules or libraries, or when you want precise control.

> In Spring Boot you rarely write `@Import` yourself, because `@SpringBootApplication` scans
> your packages. You'll still meet it in `@Enable…` annotations, in test setups
> (`@Import(TestConfig.class)` in a test class), and in multi-module projects.

---

## 8. How Job Finder could use it 🔜

```java
@Configuration
@Import({ WebConfig.class, PersistenceConfig.class, SecurityConfig.class })
public class JobFinderConfig { }
```

Each config owns one concern. `PersistenceConfig` holds the `DataSource`, `SecurityConfig`
the `PasswordEncoder`, and `JobFinderConfig` is the single entry point. (Once we move to
Spring Boot, scanning plus auto-configuration replaces most of this.)

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| Splitting config into classes but forgetting to import/register one | `NoSuchBeanDefinitionException` for its beans |
| `getBean("plainClock")` for an imported plain class | Fails: the name is the fully-qualified class name; look up by type |
| Config classes importing each other | `BeanDefinitionParsingException: A circular @Import has been detected` |
| Using `@Import` for dozens of your own components | Tedious; use `@ComponentScan` for your own packages |

## Interview questions

**Q: What does `@Import` do?**
It registers additional configuration classes, plain classes, `ImportSelector`s or
`ImportBeanDefinitionRegistrar`s in the context, so configuration can be modular.

**Q: `@Import` vs `@ComponentScan`?**
`@Import` is explicit (a named list of classes, which don't need annotations). `@ComponentScan` is
implicit (it finds all annotated classes in packages).

**Q: Can you `@Import` a class without any annotations?**
Yes. It becomes a bean, named with its fully-qualified class name.

**Q: How do `@EnableXxx` annotations work?**
They're meta-annotated with `@Import`, which pulls in a configuration class, selector or
registrar that sets up the feature.

**Q: What is an `ImportSelector` used for?**
Choosing at runtime which configuration classes to import. It's the basis of Spring Boot
auto-configuration.

**Q: What happens with circular `@Import`s?**
The context fails at startup with a "circular @Import has been detected" error.
