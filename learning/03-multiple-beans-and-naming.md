# 03 — Multiple Beans of the Same Type, Bean Names and Aliases

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

[ProjectConfig.java](../src/main/java/com/shahabuddin/config/ProjectConfig.java) now
declares **three** `Vehicle` beans:

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

[Main.java](../src/main/java/com/shahabuddin/Main.java) asks for each `Vehicle` **by name
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
| `@Primary` | 🔜 next | Mark one `@Bean` as the default for its type |
| `@Qualifier("…")` | 🔜 with injection | Choose a bean by name where it's injected |

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

**Q: What is the default name of a `@Bean`, and how do you change it?**
The method name. Change it with `@Bean("customName")` or `@Bean(name = "customName")`.

**Q: What is a bean alias?**
An additional name for the same bean. With `@Bean({"a", "b"})`, `a` is the bean name and
`b` is an alias. Both return the same instance.

**Q: What does `@Description` do?**
It adds a textual description to the bean definition, for documentation and tooling. It
has no effect at runtime.
