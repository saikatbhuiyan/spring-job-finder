# 08 — Bean Wiring (Dependency Injection) Inside Spring

## Goal

So far our beans were **independent**: a `Vehicle`, a `String`, an `Integer`. Real apps are
made of beans that **need each other**: a `Person` has a `Vehicle`, a `JobService` needs a
`JobRepository`. **Wiring** means connecting those beans, so that Spring hands one bean to
another. This is **Dependency Injection** (the DI from [note 02 §2](02-spring-ioc-and-beans.md)),
now in practice.

Status: 🧪 **all approaches verified** in a scratch program; not yet in the project code.

### The example used throughout

```java
public class Vehicle { ... }                    // prints "new Vehicle #n" in its constructor

public class Person {
    private Vehicle vehicle;
    public Person() {}
    public Person(Vehicle vehicle) { this.vehicle = vehicle; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
}
```

**"Wired correctly"** means: the `Vehicle` inside `Person` is **the same object** as the
`Vehicle` bean in the context. Not a copy, not `null`.

---

## 0. The problem: beans are not connected automatically

```java
@Configuration
class NoWire {
    @Bean Vehicle vehicle() { return new Vehicle(); }
    @Bean Person  person()  { return new Person(); }     // no vehicle given
}
```

Verified:

```text
new Vehicle #1
person.getVehicle() = null      ← both beans exist, but they don't know each other
```

Being in the same context **doesn't** connect beans. You have to wire them, in one of the
ways below.

---

## Part A: Wiring inside a `@Configuration` class

### 1. Calling the other `@Bean` method

```java
@Configuration
class MethodCall {
    @Bean Vehicle vehicle() { return new Vehicle(); }
    @Bean Person  person()  { return new Person(vehicle()); }   // call vehicle()
}
```

Verified:

```text
new Vehicle #1                                    ← created only ONCE
same object as the vehicle bean? true  (Vehicle#1 vs Vehicle#1)
```

**Wait, `vehicle()` ran `new Vehicle()` again, so why only one Vehicle?** Because the
`@Configuration` class is replaced by a **Spring-generated subclass** (CGLIB, the
`$$SpringCGLIB$$` class seen in [note 07 §3](07-stereotype-annotations.md)). That subclass
intercepts the call to `vehicle()` and **returns the existing singleton** from the container
instead of running the method body again.

#### ⚠️ The trap: "lite mode" breaks this (verified)

The same code in a class annotated with `@Component` instead of `@Configuration`:

```java
@Component
class Lite {
    @Bean Vehicle vehicle() { return new Vehicle(); }
    @Bean Person  person()  { return new Person(vehicle()); }
}
```

```text
new Vehicle #1
new Vehicle #2                                    ← a SECOND Vehicle!
same object as the vehicle bean? false  (Vehicle#2 vs Vehicle#1)
```

Without `@Configuration`, there's no subclass, so `vehicle()` is a **plain Java method call**
and creates a new object. The `Person` holds a `Vehicle` that Spring doesn't manage (no
lifecycle callbacks, not the singleton). This confirms the warning in note 02's common mistakes.

### 2. Method parameters ✅ (preferred inside `@Configuration`)

```java
@Configuration
class Param {
    @Bean Vehicle vehicle() { return new Vehicle(); }
    @Bean Person  person(Vehicle vehicle) { return new Person(vehicle); }   // Spring passes it in
}
```

Verified: `same object? true`, one Vehicle.

**Why it's better than the method call:**
- It works in **any** mode (no reliance on the CGLIB subclass).
- It works when the `Vehicle` bean is defined in a **different** config class or comes from
  a `@Component` scan. A method call only works within the same class.
- It's the same idea as constructor injection: dependencies are **declared, not fetched**.

---

## Part B: Wiring `@Component` classes with `@Autowired`

For classes Spring creates itself (scanned components), there are three **injection points**.
All three were verified to receive the same `Vehicle#1`:

```text
field: Vehicle#1, setter: Vehicle#1, constructor: Vehicle#1
```

### 3. Field injection

```java
@Component
class Person {
    @Autowired
    private Vehicle vehicle;      // Spring sets the field directly (even if private), using reflection
}
```

### 4. Setter injection

```java
@Component
class Person {
    private Vehicle vehicle;

    @Autowired
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }   // Spring calls the setter
}
```

### 5. Constructor injection ✅ **recommended**

```java
@Component
class Person {
    private final Vehicle vehicle;            // final: can never be null or changed

    public Person(Vehicle vehicle) {          // no @Autowired needed: single constructor
        this.vehicle = vehicle;
    }
}
```

With **one** constructor, Spring uses it automatically (since Spring 4.3; also verified in
note 05).

### Comparing the three

| | Field | Setter | Constructor ✅ |
|-|-------|--------|---------------|
| Can the field be `final`? | ❌ | ❌ | ✅ immutable |
| Dependency guaranteed present? | ❌ can be null | ❌ can be null | ✅ object can't exist without it |
| Works with plain `new` in unit tests? | ❌ **`null`** (verified below) | Manual setter call | ✅ `new Person(fakeVehicle)` |
| Dependencies visible in the API? | Hidden | Partly | ✅ the constructor lists them all |
| Too many dependencies obvious? | Hidden | Hidden | ✅ a 7-argument constructor is a warning sign |
| Typical use | Legacy code, quick demos | Optional / changeable dependencies | **Default for everything** |

**The test problem with field injection, verified:**

```java
new FieldP().vehicle   // → null
```

Outside Spring, nobody fills the field, so the test crashes with a `NullPointerException`.
With constructor injection, the compiler **forces** you to pass a `Vehicle`.

---

## Part C: Optional and missing dependencies (verified)

**Required dependency missing.** By default every dependency is required, and the context
fails at startup:

```text
UnsatisfiedDependencyException <- NoSuchBeanDefinitionException:
No qualifying bean of type 'w.Vehicle' available: expected at least 1 bean which
qualifies as autowire candidate.
```

**When a dependency is genuinely optional**, say so explicitly. With **no** `Vehicle` bean
defined:

| Declaration | Result when missing |
|-------------|---------------------|
| `@Autowired(required = false) Vehicle v` (field/setter) | `null` |
| Constructor parameter `Optional<Vehicle> v` | `Optional.empty` |
| Constructor parameter `ObjectProvider<Vehicle> p` → `p.getIfAvailable()` | `null` (resolved lazily, when you ask) |

`Optional` is the clearest for a constructor. `ObjectProvider` is also useful for resolving a
bean **later** instead of at startup.

---

## Part D: How Spring decides *which* bean to inject

> Deeper dive (interfaces, collections, generics, field vs parameter names):
> [note 09](09-autowiring.md).

1. Find all beans whose **type** matches the dependency.
2. **Exactly one** → inject it.
3. **None** → fail (`NoSuchBeanDefinitionException`) unless the dependency is optional.
4. **Several**: is there a `@Qualifier`? Use it. Otherwise, a `@Primary` bean? Use it. Otherwise,
   does the **parameter or field name** match a bean name? Use that bean. Otherwise, fail with
   `NoUniqueBeanDefinitionException`.

(Step 4 is covered in detail in [note 03 §8](03-multiple-beans-and-naming.md), including the
parameter-name trap with the `-parameters` compiler flag.)

---

## Part E: Wiring problems (verified)

### Circular dependencies

> Full deep dive (early references, order-dependent failures, design fixes):
> [note 10](10-circular-dependencies.md).

`A` needs `B` and `B` needs `A`.

**With constructors, it fails at startup:**

```java
@Component class CA { CA(CB b) {} }
@Component class CB { CB(CA a) {} }
```

```text
UnsatisfiedDependencyException <- BeanCurrentlyInCreationException:
Error creating bean with name 'w.Exp12$CA': Requested bean is currently in creation:
Is there an unresolvable circular reference ...?
```

It's impossible: to build `CA` you need a finished `CB`, which needs a finished `CA`.

**With fields, it "works" in plain Spring:**

```java
@Component class FA { @Autowired FB b; }
@Component class FB { @Autowired FA a; }
```

```text
ok: FA.b=true, FB.a=true
```

Spring creates both objects first and fills the fields afterwards. **This isn't a point
in favour of field injection.** A circular dependency is a **design problem** (two classes
that each can't exist without the other). Constructor injection *exposes* it early. Spring
Boot even disables circular references by default (since Boot 2.6). **Fix the design**:
extract the shared logic into a third bean, or rethink which class owns what.

### Two constructors, neither marked

```java
@Component class TwoCtors {
    TwoCtors(Vehicle v) { ... }
    TwoCtors(Vehicle v, String s) { ... }
}
```

```text
BeanCreationException <- NoSuchMethodException: w.Exp12$TwoCtors.<init>()
```

Spring can't choose, so it falls back to looking for a no-argument constructor, which doesn't
exist. **Fix:** put `@Autowired` on the constructor Spring should use. Verified:
`Spring used constructor: 2-arg (@Autowired)`.

---

## Summary: which wiring to use

| Situation | Use |
|-----------|-----|
| `@Bean` method needs another bean | **Method parameter**: `@Bean Person person(Vehicle v)` |
| Your own `@Component` / `@Service` class | **Constructor injection**, `final` fields, no `@Autowired` (single constructor) |
| Several constructors | `@Autowired` on the one Spring should use |
| Dependency really is optional | `Optional<T>` or `ObjectProvider<T>` |
| Several beans of the type | `@Qualifier` / `@Primary` (note 03 §8) |
| Avoid | Field injection; calling `@Bean` methods outside `@Configuration`; circular dependencies |

In Job Finder: `JobController(JobService)`, `JobService(JobRepository)`, all wired by constructor
(the demo in [note 05 §4](05-backend-layers.md) already does this).

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| Defining both beans but never wiring them | Dependency is `null` |
| Calling a `@Bean` method from a `@Component` class (lite mode) | A second, unmanaged instance (verified) |
| Field injection + `new` in a test | `NullPointerException`; the field is never set |
| Circular constructor dependencies | `BeanCurrentlyInCreationException` at startup |
| Two constructors without `@Autowired` | `BeanCreationException` (no default constructor) |
| Required bean missing | `UnsatisfiedDependencyException` at startup |

## Interview questions

**Q: What is bean wiring?**
Connecting beans that depend on each other, so the container injects one bean into another.

**Q: What are the types of dependency injection in Spring?**
Constructor, setter, and field injection.

**Q: Which injection type is recommended and why?**
Constructor injection: it allows `final` fields, guarantees dependencies are present, makes
dependencies explicit, and makes unit tests easy without Spring.

**Q: Is `@Autowired` required on a constructor?**
Not if the class has exactly one constructor. With several, mark the one to use.

**Q: Calling one `@Bean` method from another: do you get two objects?**
Not in a `@Configuration` class: the CGLIB proxy returns the singleton. In lite mode (no
`@Configuration`), yes, you get a new object each call.

**Q: How do you inject an optional dependency?**
`@Autowired(required = false)`, `Optional<T>`, or `ObjectProvider<T>`.

**Q: How does Spring handle circular dependencies?**
With constructor injection it fails (`BeanCurrentlyInCreationException`). With field/setter
injection plain Spring can resolve it, but it's a design smell; Spring Boot disallows it by
default.
