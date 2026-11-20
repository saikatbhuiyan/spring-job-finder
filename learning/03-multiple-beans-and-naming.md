# 03 — Multiple Beans of the Same Type, Bean Names and Aliases

> **History note (2026-11-20):** the project has since moved to **Spring Boot** (see
> [note 15](15-spring-boot-application-and-health.md)). The plain-Spring code shown in this note
> (`Main`, `Vehicle`, `ProjectConfig`) was removed from `main` and is preserved at the git tag
> [`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).
> The concepts are unchanged; Spring Boot builds on all of them.

## Goal

Real applications often have several beans of the **same type**: two `DataSource`s, three
`Vehicle`s, several `PaymentGateway`s. This note covers:

- what happens when Spring has more than one bean of a type,
- how to name beans yourself, with `@Bean("…")`,
- giving one bean several names (aliases),
- documenting a bean with `@Description`.

Builds on [note 02](02-spring-ioc-and-beans.md).

---

## 1. In this project

> **History (2026-11-20):** these three `@Bean` Vehicle methods were later removed, when
> `Vehicle` became a scanned `@Component` (see [note 04 §4](04-component-scan-and-bean-lifecycle.md)).
> The code below is how [ProjectConfig.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/config/ProjectConfig.java)
> looked at commit `bee9000` (`git show bee9000:src/main/java/com/shahabuddin/config/ProjectConfig.java`).
> The concepts still apply.

`ProjectConfig` declared **three** `Vehicle` beans:

```java
@Configuration
public class ProjectConfig {

    @Bean
    Vehicle vehicle1() {              // bean name: "vehicle1"
        var veh = new Vehicle();
        veh.setName("Tesla");
        return veh;
    }

    @Bean
    Vehicle vehicle2() {              // bean name: "vehicle2"
        var veh = new Vehicle();
        veh.setName("BYD");
        return veh;
    }

    @Bean({"ferrari", "myFavoriteVehicle"})       // name: "ferrari", alias: "myFavoriteVehicle"
    @Description("This is a Vehicle class Bean")
    Vehicle vehicle3() {                          // "vehicle3" is NOT a bean name
        var veh = new Vehicle();
        veh.setName("Toyota");
        return veh;
    }

    @Bean String hello() { return "Hello World"; }
    @Bean Integer luckyNumber() { return 10; }
}
```

[Main.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/Main.java) asks for each `Vehicle` **by name
and type**:

```java
var veh = context.getBean("vehicle1", Vehicle.class);
IO.println("Vehicle name from Spring Context is: " + veh.getName());

var veh2 = context.getBean("vehicle2", Vehicle.class);
IO.println("Vehicle name from Spring Context is: " + veh2.getName());
```

Output (verified):

```text
Hello and welcome!
Vehicle name from non-spring context is: Audi
Vehicle name from Spring Context is: Tesla
Vehicle name from Spring Context is: BYD
String value  from Spring Context is: Hello World
Integer value  from Spring Context is: 10
String value  from Spring Context is: Hello World
```

---

## 2. More than one bean of a type: asking by type fails

With three `Vehicle` beans, this line from the first version of `Main` no longer works:

```java
context.getBean(Vehicle.class);
```

Verified error:

```text
NoUniqueBeanDefinitionException: No qualifying bean of type 'com.shahabuddin.beans.Vehicle'
available: expected single matching bean but found 3: vehicle1,vehicle2,ferrari
```

Read the message carefully. Spring tells you **how many** it found and **their names**.
That's exactly what you need to fix it.

Ways to resolve it:

| Option | Status | How |
|--------|--------|-----|
| Ask by name + type | ✅ applied | `getBean("vehicle1", Vehicle.class)` |
| `@Primary` | 🧪 verified | Mark one `@Bean` as the default for its type |
| `@Qualifier("…")` | 🧪 verified | Choose a bean by name where it's injected |

**All the fixes, each tested: see §8.**

Note that `getBean(String.class)` and `getBean(Integer.class)` **still work**, because
there's still only one bean of each of those types. The problem is per type.

---

## 3. Naming a bean yourself: `@Bean("name")`

| Declaration | Bean name(s) |
|-------------|--------------|
| `@Bean Vehicle vehicle1()` | `vehicle1` (the method name) |
| `@Bean("ferrari") Vehicle vehicle3()` | `ferrari` only |
| `@Bean(name = "ferrari")` | same as above; `name` and `value` are the same attribute |
| `@Bean({"ferrari", "myFavoriteVehicle"}) Vehicle vehicle3()` | `ferrari` (main name) + `myFavoriteVehicle` (alias) |

⚠️ **Once you give an explicit name, the method name is no longer a bean name.**
Verified:

```java
context.getBean("vehicle3");   // ❌ NoSuchBeanDefinitionException
context.getBean("ferrari");    // ✅ Toyota
```

### Why name beans explicitly?

- The method name is an implementation detail; the bean name is the **public ID**
  other code uses. Explicit names let you rename the method without breaking callers.
- Meaningful names (`primaryDataSource`, `reportingDataSource`) beat `vehicle1`, `vehicle2`.

---

## 4. Aliases: one bean, several names

```java
@Bean({"ferrari", "myFavoriteVehicle"})
```

- The **first** name is the bean's real (canonical) name: `ferrari`.
- Every other name is an **alias**: another label for the **same object**.

Verified:

```java
context.getBean("ferrari", Vehicle.class).getName();            // Toyota
context.getBean("myFavoriteVehicle", Vehicle.class).getName();  // Toyota
context.getBean("ferrari") == context.getBean("myFavoriteVehicle");  // true: same object
context.getAliases("ferrari");                                  // [myFavoriteVehicle]
```

An alias does **not** create a second bean. `getBeanDefinitionNames()` lists only `ferrari`:

```text
projectConfig, vehicle1, vehicle2, ferrari, hello, luckyNumber
```

**When are aliases useful?** When different parts of a large system (or different
modules) refer to the same thing by different names. For example, one module calls the database
`dataSource` while another calls it `mainDb`. Aliases let both work without duplicating the bean.

---

## 5. `@Description`: documentation for a bean

```java
@Bean({"ferrari", "myFavoriteVehicle"})
@Description("This is a Vehicle class Bean")
Vehicle vehicle3() { ... }
```

`@Description` attaches a human-readable text to the **bean definition** (Spring's recipe
for the bean). **It doesn't change behavior at all.** It's metadata for people and
tooling, such as monitoring tools (JMX) that list beans.

You can read it back. Verified:

```java
context.getBeanDefinition("ferrari").getDescription();   // "This is a Vehicle class Bean"
context.getBeanDefinition("vehicle1").getDescription();  // null: no @Description on it
```

In practice `@Description` is rarely used. A good Javadoc comment on the method is more
common. It's still worth knowing because it shows that **Spring keeps a definition (metadata)
for every bean**, separate from the bean object itself.

---

## 6. 🧪 Learned, not applied: finding all beans of a type

Useful for exploring the container (verified):

```java
context.getBeanNamesForType(Vehicle.class);   // [vehicle1, vehicle2, ferrari]
context.getBeansOfType(Vehicle.class);        // Map: {vehicle1=…, vehicle2=…, ferrari=…}
```

`getBeansOfType` returns a `Map<String, Vehicle>` of name → bean. Later, with dependency
injection, you can get the same thing by injecting a `List<Vehicle>` or a
`Map<String, Vehicle>`.

---

## 7. ✅ Bug found and fixed (2026-11-19): printing the wrong variable

The first version of the new `Main` code was:

```java
var veh2 = context.getBean("vehicle2", Vehicle.class);
IO.println("Vehicle name from Spring Context is: " + veh.getName());   // ❌ veh, not veh2
```

The output showed **Tesla twice** and never BYD. Spring was fine. The bug was plain
Java: `veh2` was fetched and never used, and the line printed `veh` again.

**Lessons:**

1. **Read the output and compare it with what you expect.** "It ran without errors" is
   not the same as "it's correct".
2. **An unused variable is a warning sign.** IntelliJ greys out `veh2` and warns
   "Variable is never used". Treat those warnings as possible bugs.
3. Copy-pasting a line and forgetting to change one part is one of the most common bugs
   in all of programming.

---

## 8. 🧪 Solving `NoUniqueBeanDefinitionException`: every option (verified)

### What the error means

You asked Spring for **one** bean of a type, and it found **more than one**, so it can't
choose:

```text
NoUniqueBeanDefinitionException: No qualifying bean of type 'Vehicle' available:
expected single matching bean but found 2: audi,bmw
```

It happens in two situations:

1. **`getBean(Vehicle.class)`**, which is what this project hit.
2. **Dependency injection**: a bean *needs* a `Vehicle` (a constructor or `@Bean` method
   parameter), and Spring can't decide which one to pass. Then the context **fails at
   startup**, and the error is wrapped (verified):

```text
UnsatisfiedDependencyException: Error creating bean with name 'person' ...
  Unsatisfied dependency expressed through method 'person' parameter 0:
  No qualifying bean of type 'Vehicle' available: expected single matching bean but found 2: audi,bmw
```

> **Reading tip:** with a wrapped error, read to the **innermost cause** (the last
> "Caused by:" in the stack trace). That's the real problem.

### The test setup

Two beans of the same type, and a `Person` that needs one:

```java
@Bean Vehicle audi() { return new Vehicle("Audi"); }
@Bean Vehicle bmw()  { return new Vehicle("BMW");  }

@Bean Person person(Vehicle v) { return new Person(v); }   // which Vehicle? 💥
```

### Fix 1: Look up by name

```java
context.getBean("bmw", Vehicle.class);   // BMW
```

Only for `getBean` calls (✅ used in this project, §1). It doesn't help with injection.

### Fix 2: `@Primary`, "use this one by default"

```java
@Bean @Primary Vehicle audi() { ... }
@Bean          Vehicle bmw()  { ... }
```

Verified:

```text
getBean(Vehicle.class) = Audi
Person got             = Audi
```

**Use when** one bean is the normal choice and the others are special cases. Only
**one** bean per type should be `@Primary`. Two would bring the ambiguity back.

### Fix 3: `@Qualifier("name")`, "I want exactly this one"

```java
@Bean PersonQ personQ(@Qualifier("bmw") Vehicle v) { return new PersonQ(v); }
// or on a constructor:  PersonQ(@Qualifier("bmw") Vehicle v) { ... }
```

Verified, **even with `@Primary` on `audi`**:

```text
@Qualifier("bmw") overrides @Primary -> BMW
```

**Rule: `@Qualifier` beats `@Primary`.** `@Primary` is the default; `@Qualifier` is an
explicit request at one injection point.

### Fix 4: `@Fallback`, "use this one only if nothing else fits"

New in Spring 6.2, so available in our Spring 7. It's the opposite of `@Primary`:

```java
@Bean @Fallback Vehicle audi() { ... }
@Bean           Vehicle bmw()  { ... }
```

Verified: `getBean(Vehicle.class) = BMW`. The non-fallback bean wins.

**Use when** you provide a default bean that should step aside whenever someone
defines their own.

### Fix 5: Match by parameter name (works only in some builds) ⚠️

If the parameter name matches a bean name, Spring uses that bean:

```java
@Bean PersonByName p(Vehicle bmw) { ... }   // parameter "bmw" → bean "bmw"
```

Verified, with a catch:

| Compiled… | Result |
|-----------|--------|
| Without the `-parameters` flag (plain `javac` / **this project's current `pom.xml`**) | ❌ `NoUniqueBeanDefinitionException`: Spring can't see parameter names |
| With `javac -parameters` | ✅ BMW |

Java throws parameter names away at compile time unless `-parameters` is set. Spring Boot's
parent POM turns it on, so this "works in Boot, fails in plain Spring". **Don't rely on
it.** Use `@Qualifier`, which works regardless of compiler flags.

### Fix 6: Take all of them, with `List` or `Map`

When you really want **every** bean of a type:

```java
@Bean Garage garage(List<Vehicle> all, Map<String, Vehicle> byName) { ... }
```

Verified:

```text
List = [Audi, BMW], Map = {audi=Audi, bmw=BMW}
```

The `Map` key is the bean name. **Use when** you need all implementations, e.g. every
`PaymentProvider`, or every `JobImporter` (LinkedIn, Indeed, …) in Job Finder.

### Fix 7: Remove the accidental duplicate

Sometimes the second bean is a **mistake**: a class with `@Component` *and* a `@Bean` method
for it, or two scans covering the same package. This project had exactly this on
2026-11-20 ([note 04 §4](04-component-scan-and-bean-lifecycle.md)). The fix is to delete
the duplicate, not to add `@Primary`.

### Which fix to choose

| Situation | Fix |
|-----------|-----|
| The second bean shouldn't exist | **Remove it** (Fix 7) |
| One bean is the usual choice | **`@Primary`** (Fix 2) |
| One particular place needs a specific bean | **`@Qualifier`** (Fix 3) |
| A library/default bean the user may replace | **`@Fallback`** (Fix 4) |
| You need all of them | **`List` / `Map`** injection (Fix 6) |
| Quick lookup in `main` | `getBean(name, type)` (Fix 1) |
| — | ❌ Avoid relying on parameter names (Fix 5) |

---

## 9. 🧪 Every way to name a bean (verified)

Every bean has **exactly one name** (its ID) and **zero or more aliases**. Here are all the
ways that name gets decided.

### A. `@Bean` methods

| Declaration | Bean name | Aliases |
|-------------|-----------|---------|
| `@Bean Vehicle vehicle1()` | `vehicle1` (method name) | — |
| `@Bean("ferrari") Vehicle vehicle3()` | `ferrari` | — |
| `@Bean(name = "ferrari")` | `ferrari` (`name` and `value` mean the same thing) | — |
| `@Bean({"ferrari", "myFavoriteVehicle"})` | `ferrari` (the first entry) | `myFavoriteVehicle` |

Once an explicit name is given, the **method name is not a bean name anymore** (§3).

### B. `@Component` and stereotypes

| Class | Bean name | Rule |
|-------|-----------|------|
| `@Component class Vehicle` | `vehicle` | Class name, first letter lowercased |
| `@Service class JobService` | `jobService` | Same rule for every stereotype |
| `@Component class A` | `a` | Same rule |
| `@Component class URLParser` | **`URLParser`** ⚠️ | If the **first two letters are both uppercase**, the name is kept as is |
| `@Component static class Inner` inside `Outer` | `outer.Inner` | Nested class: `Outer.Inner`, first letter lowercased |
| `@Repository("jobRepo") class Named` | `jobRepo` | Explicit name, the same for `@Component("…")`, `@Service("…")`, `@Controller("…")` |
| `@Configuration("myCfg") class NamedCfg` | `myCfg` | Configuration classes are components too |

All of these were verified by printing `getBeanDefinitionNames()`. The `URLParser` rule comes from
the JavaBeans standard (`Introspector.decapitalize`), so `getBean("uRLParser")` would **not**
work.

> `@Component` takes **one** name only. Unlike `@Bean`, it has no array form for
> aliases. Add aliases in code (option D) if you really need them.

### C. A different naming strategy for a whole scan

```java
@ComponentScan(basePackages = "n1",
               nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
```

Verified. Scanned beans are named after their **full class name**:

```text
[n1.A, n1.JobService, jobRepo, n1.Outer$Inner, n1.URLParser]
```

Note that `jobRepo` kept its explicit name, because **an explicit name always beats the
generator**. This is useful in big projects where two packages contain classes with the
same simple name (see the clash below).

### D. Registering a bean in code

> All the programmatic options (incl. Spring 7 `BeanRegistrar`): [note 12](12-programmatic-bean-registration.md).

```java
var ctx = new AnnotationConfigApplicationContext();
ctx.registerBean("myCar", StringBuilder.class, () -> new StringBuilder("registered in code"));
ctx.refresh();                              // start the container
ctx.registerAlias("myCar", "dailyDriver");  // add an alias
ctx.getBean("dailyDriver");                 // "registered in code"
```

Verified. This is rarely used in applications, but it shows what the annotations do behind
the scenes: they register a name → definition.

### ⚠️ Name clash: same class name in two packages

```java
package clash.a;  @Component public class Helper {}
package clash.b;  @Component public class Helper {}
```

Both default to the name `helper`. Verified: the context **fails at startup**:

```text
ConflictingBeanDefinitionException: Annotation-specified bean name 'helper' for bean class
[clash.b.Helper] conflicts with existing, non-compatible bean definition of same name and
class [clash.a.Helper]
```

Fix it by giving one an explicit name (`@Component("legacyHelper")`) or by using the
fully-qualified name generator (option C).

### Naming best practices

1. Let the defaults work for your own classes (`jobService`, `jobRepository`).
2. Give **explicit, meaningful names** when there are several beans of a type
   (`primaryDataSource`, not `dataSource2`).
3. Avoid aliases unless different modules really need different names for one bean.
4. Remember that names are case-sensitive: `luckyNumber` ≠ `LuckyNumber` (note 02 §6).

---

## 10. 🧪 `@Primary` in depth (verified)

> `@Qualifier` in depth and the full `@Qualifier` vs `@Primary` comparison:
> [note 11](11-primary-vs-qualifier.md).

### What it does

`@Primary` marks one bean as **the default** when several beans of the same type exist and
the caller asks **by type** without saying which one.

### Where you can put it

**On a `@Bean` method:**

```java
@Bean @Primary Vehicle audi() { ... }
@Bean          Vehicle bmw()  { ... }
```

**On a `@Component` class:**

```java
public interface Engine {}

@Component @Primary public class Petrol   implements Engine {}
@Component          public class Electric implements Engine {}
```

Verified:

```text
getBean(Engine.class)        = Petrol     ← @Primary wins a lookup by type
getBean("electric")          = Electric   ← a lookup by name ignores @Primary
getBeansOfType(Engine.class) = [electric, petrol]   ← "give me all" ignores @Primary
isPrimary(petrol)            = true       ← stored in the bean definition
```

This interface example is the **most common real-world use**: several implementations of
one interface, with `@Primary` choosing the default implementation.

### Rules

| Rule | Verified result |
|------|-----------------|
| Asking by **type** → the primary bean is returned | Audi / Petrol |
| Asking by **name** → `@Primary` doesn't matter | Electric |
| Asking for **all** (`getBeansOfType`, `List<Engine>`) → every bean | electric, petrol |
| `@Qualifier("…")` at the injection point **beats** `@Primary` | BMW (§8, Fix 3) |
| **Two** `@Primary` beans of the same type | ❌ fails, see below |

Two primaries:

```java
@Bean @Primary String a() { return "A"; }
@Bean @Primary String b() { return "B"; }
```

```text
NoUniqueBeanDefinitionException: No qualifying bean of type 'java.lang.String' available:
more than one 'primary' bean found among candidates: [a, b]
```

Note that it's the **same exception** with a different message. Read the message to
tell them apart.

### `@Primary` vs `@Qualifier` vs `@Fallback`

| | Declared on | Meaning |
|-|-------------|---------|
| `@Primary` | The bean | "Pick me by default." |
| `@Fallback` | The bean | "Pick me only if there's nothing else." (Spring 6.2+) |
| `@Qualifier("x")` | The **injection point** | "I want exactly bean x." Overrides both of the above |

### When to use `@Primary`

- ✅ One implementation is normal and the others are special: e.g. `PostgresJobRepository`
  is `@Primary`, and `InMemoryJobRepository` is only used in certain tests.
- ✅ Overriding a library's bean with your own without touching the library's code.
- ❌ Not as a quick fix for an **accidental** duplicate. Delete the duplicate instead (§8 Fix 7).

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `getBean(Vehicle.class)` with several `Vehicle` beans | `NoUniqueBeanDefinitionException` |
| Using the method name after giving `@Bean("ferrari")` | `NoSuchBeanDefinitionException` for `vehicle3` |
| Thinking an alias creates a second object | It doesn't; both names point to the same singleton |
| Expecting `@Description` to change behavior | It's metadata only |
| Two `@Bean` methods with the same explicit name | Bean definition override conflict; avoid duplicate names |

## Interview questions

**Q: What happens if two beans of the same type exist and you request by type?**
`NoUniqueBeanDefinitionException`. Fix it by requesting by name, marking one `@Primary`, or
using `@Qualifier` at the injection point.

**Q: `@Primary` and `@Qualifier` are both present. Which wins?**
`@Qualifier`. `@Primary` is a default; `@Qualifier` is an explicit choice at the injection
point.

**Q: What is `@Fallback`?**
Spring 6.2+. It marks a bean to use only when no other candidate fits, the opposite of
`@Primary`.

**Q: How do you inject all beans of a type?**
Inject `List<Vehicle>` (all beans) or `Map<String, Vehicle>` (bean name → bean).

**Q: Why can matching by parameter name fail?**
Parameter names are only kept in the bytecode when compiling with `-parameters`. Spring Boot
enables it; a plain Maven project doesn't by default.

**Q: What is the default name of a `@Bean`, and how do you change it?**
The method name. Change it with `@Bean("customName")` or `@Bean(name = "customName")`.

**Q: What is the default bean name of `@Component class URLParser`?**
`URLParser`. When the first two letters are uppercase, the name isn't lowercased (the
JavaBeans `Introspector.decapitalize` rule).

**Q: Two `@Component` classes named `Helper` in different packages. What happens?**
`ConflictingBeanDefinitionException` at startup, because both want the name `helper`. Give one
an explicit name, or use `FullyQualifiedAnnotationBeanNameGenerator`.

**Q: Does `@Primary` affect `getBean("name")` or `List<T>` injection?**
No. It only decides which bean to use when a single bean is requested **by type**.

**Q: What if two beans of the same type are both `@Primary`?**
`NoUniqueBeanDefinitionException: more than one 'primary' bean found`.

**Q: What is a bean alias?**
An additional name for the same bean. With `@Bean({"a", "b"})`, `a` is the bean name and
`b` is an alias. Both return the same instance.

**Q: What does `@Description` do?**
It adds a textual description to the bean definition, for documentation and tooling. It
has no effect at runtime.
