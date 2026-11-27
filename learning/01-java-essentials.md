# 01 — Java Essentials Used in This Project

> **History note (2026-11-20):** the project has since moved to **Spring Boot** (see
> [note 15](15-spring-boot-application-and-health.md)). The plain-Spring code shown in this note
> (`Main`, `Vehicle`, `ProjectConfig`) was removed from `main` and is preserved at the git tag
> [`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics).
> The concepts are unchanged; Spring Boot builds on all of them.

## Goal

Understand every piece of plain Java syntax that appears in the code so far, so the Spring
parts in note 02 make sense. This note explains only the Java that's actually in the
project; new Java features get added here as they show up.

---

## 1. Packages and imports

```java
package com.shahabuddin.beans;
```

A **package** is a namespace, like a folder for classes. It prevents name clashes:
your `Vehicle` and some library's `Vehicle` can both exist because their full names differ:

- `com.shahabuddin.beans.Vehicle` ← the **fully-qualified name**

```java
import com.shahabuddin.beans.Vehicle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
```

An **import** lets you write the short name (`Vehicle`) instead of the full one.
Classes in `java.lang` (`String`, `Integer`, `IO`) are imported automatically.

How this project uses packages:


| Package                  | Purpose                                    |
| -------------------------- | -------------------------------------------- |
| `com.shahabuddin`        | Entry point (`Main`)                       |
| `com.shahabuddin.beans`  | Plain data classes that Spring will manage |
| `com.shahabuddin.config` | Spring configuration classes               |

---

## 2. Classes, objects and fields

[Vehicle.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/beans/Vehicle.java):

```java
public class Vehicle {
    private String name;
    ...
}
```

- A **class** is a blueprint. `Vehicle` describes what every vehicle has.
- An **object** (or **instance**) is one concrete thing built from the blueprint.
- A **field** (`name`) is data that each object holds.

```java
Vehicle vehicle = new Vehicle();   // create an object with `new`
```

`new Vehicle()` calls the **constructor**. We didn't write one, so Java provides a hidden
**default no-argument constructor**: `public Vehicle() {}`.

---

## 3. Access modifiers and encapsulation


| Modifier                    | Visible from               |
| ----------------------------- | ---------------------------- |
| `public`                    | Everywhere                 |
| `protected`                 | Same package + subclasses  |
| *(none)*: "package-private" | Same package only          |
| `private`                   | Only inside the same class |

`name` is `private`, so no other class can touch it directly. They must go through methods.
This is **encapsulation**: the class controls how its data is read and changed.

---

## 4. Getters and setters (the JavaBean convention)

```java
public String getName() { return name; }

public void setName(String name) { this.name = name; }
```

- **Getter** `getX()` reads the field. **Setter** `setX(value)` writes it.
- `this.name` means "the field of *this* object". It's needed here because the parameter
  is also called `name`, and `this.` tells them apart.
- `void` means the method returns nothing.

A class with a no-arg constructor, private fields, and `getX`/`setX` methods follows the
**JavaBean** convention. Many frameworks (Spring, Jackson for JSON, Hibernate) rely on
these names to read and write properties automatically.

> **"JavaBean" ≠ "Spring bean."** A JavaBean is a coding convention. A *Spring bean* is
> any object that the Spring container manages (see note 02). An object can be one, both, or
> neither.

---

## 5. `static` vs instance

```java
static void main() { ... }
```

- **Instance** members belong to each object. You need `new Vehicle()` before calling
  `getName()`.
- **`static`** members belong to the class itself. You can call them without creating an
  object. That's why `main` is usually `static`: the JVM calls it before any objects exist.

---

## 6. The `main` method and `IO.println` (Java 25 features)

[Main.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/Main.java):

```java
public class Main {
    static void main() {
        IO.println("Hello and welcome!");
    }
}
```

If you look at older tutorials, you'll see this instead:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello and welcome!");
    }
}
```

Both work in Java 25. The shorter form comes from **JEP 512 (Compact Source Files and
Instance Main Methods)**, which became final in Java 25:


| Old style                                | Java 25 style                              | Notes                                                |
| ------------------------------------------ | -------------------------------------------- | ------------------------------------------------------ |
| `public static void main(String[] args)` | `static void main()` or even `void main()` | `public` and `args` are optional now                 |
| `System.out.println(...)`                | `IO.println(...)`                          | `java.lang.IO` is a new helper class for console I/O |

**Recommendation:** know both. Most existing code, books and interviews use the classic
form, and Spring Boot's generated apps use `public static void main(String[] args)`.

---

## 7. `var`: local variable type inference

```java
var context = new AnnotationConfigApplicationContext(ProjectConfig.class);
var veh = context.getBean(Vehicle.class);
```

`var` asks the compiler to figure out the type from the right-hand side. The variable is
**still statically typed**: `veh` *is* a `Vehicle`, and you can't assign a `String` to it
later.

Rules:

- Works only for **local variables** (inside methods), not for fields or parameters.
- Must be initialized on the same line.
- Use it when the type is obvious from the right side; spell out the type when it isn't.

---

## 8. Wrapper types: `int` vs `Integer`

```java
@Bean
Integer luckyNumber() { return 10; }
```

Java has **primitive** types (`int`, `double`, `boolean`) and matching **wrapper** classes
(`Integer`, `Double`, `Boolean`). Primitives aren't objects. Spring only manages
**objects**, so the bean type must be `Integer`, not `int`.

`return 10;` works because Java **autoboxes** the `int` 10 into an `Integer` automatically.

---

## 9. Casting

The first version of `Main` used a cast:

```java
String hello1 = (String) context.getBean("hello");
```

`getBean(String name)` returns the general type `Object`, because Spring can't know
which type that name refers to. `(String)` is a **cast**: "trust me, this object is really a
`String`." If it isn't, Java throws a `ClassCastException` at runtime.

✅ **Fixed (2026-11-19).** The project now uses the overload that takes the type, so no
cast is needed (see note 02 §5):

```java
String hello1 = context.getBean("hello", String.class);
```

**Lesson:** a cast tells the compiler "stop checking this". Prefer APIs that let the compiler
check the type for you.

---

## 10. try-with-resources

Some objects hold resources (files, network connections, a Spring container) and must be
**closed** when you're done. Java's `try`-with-resources statement does this automatically:

```java
try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) {
    // use context
}   // context.close() runs here automatically
```

- Anything declared in the `try ( ... )` parentheses must implement `AutoCloseable`.
- `close()` runs when the block ends, **even if an exception is thrown** inside it.
- It replaces the old, error-prone pattern of calling `close()` yourself in a `finally` block.
- The variable only exists inside the block. Use it after the `}` and you get a compile
  error.

Used in [Main.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/Main.java) since 2026-11-19
(see note 02 §9).

---

## 11. Annotations

```java
@Configuration
public class ProjectConfig {
    @Bean
    Vehicle vehicle() { ... }
}
```

An **annotation** (`@Something`) is metadata attached to code. It doesn't do anything on its
own. Some other code (the compiler, or a framework like Spring) reads it and acts on it.
In this project, Spring reads `@Configuration` and `@Bean` to decide which objects to create.
Note 02 covers them.

---

## 12. Comments

```java
// single line
/* multi
   line */
/** Javadoc: documentation for a class or method (shown in IDE tooltips). */
```

---

## 13. Naming conventions

Java naming rules are conventions, not compiler rules, but everyone follows them:


| Thing            | Convention         | Example                  |
| ------------------ | -------------------- | -------------------------- |
| Class            | `PascalCase`       | `ProjectConfig`          |
| Method, variable | `camelCase`        | `getName`, `luckyNumber` |
| Constant         | `UPPER_SNAKE_CASE` | `MAX_RESULTS`            |
| Package          | all lowercase      | `com.shahabuddin.beans`  |

✅ **Fixed (2026-11-19).** In [ProjectConfig.java](https://github.com/saikatbhuiyan/spring-job-finder/blob/spring-core-basics/src/main/java/com/shahabuddin/config/ProjectConfig.java)
the method was first named `LuckyNumber()`, which broke this rule. It's now `luckyNumber()`.
This matters more than style in Spring, because **the method name becomes the bean's
name**. See note 02 §6.

---

## Interview questions

**Q: What is encapsulation?**
Hiding a class's data behind `private` fields and exposing controlled access through
methods.

**Q: Difference between `int` and `Integer`?**
`int` is a primitive (not an object, can't be `null`). `Integer` is an object wrapper (can be
`null`, can be used in collections and generics, and can be a Spring bean).

**Q: Does `var` make Java dynamically typed?**
No. The type is inferred at compile time and fixed after that.

**Q: What does try-with-resources do?**
It automatically calls `close()` on any `AutoCloseable` declared in the `try (...)`, even
if an exception is thrown.

**Q: What is a JavaBean?**
A class with a public no-arg constructor, private fields, and public getters/setters.
