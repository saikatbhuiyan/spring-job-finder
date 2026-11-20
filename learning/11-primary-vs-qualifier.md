# 11 — `@Primary`, `@Qualifier`, and `@Qualifier` vs `@Primary`

## Goal

When several beans share a type, Spring needs help choosing. Two annotations solve it from
**opposite sides**:

- **`@Primary`**: the **bean** says "pick me by default".
- **`@Qualifier`**: the **consumer** says "I want exactly that one".

This note covers both, the differences between them, and when to use which. Builds on
[note 03 §8–§10](03-multiple-beans-and-naming.md) and [note 09 §3](09-autowiring.md).
All results verified on Spring 7.0.10.

Status: 🧪 learned and verified; not yet in the project code.

---

## Part 1: `@Primary` (recap)

Full details, verified, are in [note 03 §10](03-multiple-beans-and-naming.md). In short:

```java
@Bean @Primary Vehicle audi() { return new Vehicle("Audi"); }
@Bean          Vehicle bmw()  { return new Vehicle("BMW");  }

@Bean Person defaultPerson(Vehicle v) { ... }    // no qualifier → Audi
```

Verified: `no qualifier -> Audi (@Primary)`.

- Declared on the **bean** (`@Bean` method or `@Component` class).
- Used only when a **single** bean is requested **by type** with nothing more specific.
- Ignored by name lookups and by `List`/`Map` injection.
- Two `@Primary` beans of one type → `NoUniqueBeanDefinitionException: more than one 'primary' bean found`.

---

## Part 2: `@Qualifier` in depth

### 2.1 Where you can put it (verified)

The same `@Qualifier("bmw")` works at every injection point, **even though `audi` is
`@Primary`**:

```java
// field
@Autowired @Qualifier("bmw") Vehicle v;

// setter (on the parameter)
@Autowired void setV(@Qualifier("bmw") Vehicle v) { ... }

// constructor parameter ✅ recommended
CtorQ(@Qualifier("bmw") Vehicle v) { ... }

// @Bean method parameter
@Bean Person person(@Qualifier("bmw") Vehicle v) { return new Person(v); }
```

```text
field=BMW setter=BMW constructor=BMW @Bean param=BMW
```

### 2.2 What the qualifier value matches

`@Qualifier("x")` at the injection point matches a bean that:

1. has a **qualifier label** `"x"` declared on it (§2.4), or
2. is **named** `"x"` (fallback).

In 2.1, `"bmw"` matched the **bean name** (the method `bmw()`).

### 2.3 ⚠️ A wrong qualifier fails, with no fallback (verified)

```java
Typo(@Qualifier("tesla") Vehicle v) { ... }    // beans are audi and bmw
```

```text
NoSuchBeanDefinitionException: No qualifying bean of type 'Vehicle' available:
expected at least 1 bean which qualifies as autowire candidate.
Dependency annotations: {@Qualifier("tesla")}
```

Spring does **not** fall back to the `@Primary` bean or to "any `Vehicle`". A qualifier is a
**strict requirement**. The error even prints the annotation, so typos are easy to find.

### 2.4 Qualifier labels on the bean side (groups)

`@Qualifier` can also go on the **bean definition** as a label, and several beans can share
one label:

```java
@Bean @Qualifier("electric") Vehicle tesla() { ... }
@Bean @Qualifier("electric") Vehicle byd()   { ... }
@Bean                        Vehicle audi()  { ... }

@Autowired @Qualifier("electric") List<Vehicle> electric;   // filtered!
@Autowired                        List<Vehicle> all;
```

Verified:

```text
@Qualifier("electric") List = [Tesla, BYD], plain List = [Tesla, BYD, Audi]
```

**`@Qualifier` filters collections.** Unlike `@Primary`, which collections ignore. But
asking for a **single** `@Qualifier("electric") Vehicle` when two beans carry the label fails:

```text
NoUniqueBeanDefinitionException: ... expected single matching bean but found 2: tesla,byd
```

### 2.5 On a `@Component` class (verified)

```java
public interface Engine {}
@Component @Qualifier("fast") class V8      implements Engine {}
@Component                    class Inline4 implements Engine {}

@Component class Racer { Racer(@Qualifier("fast") Engine e) { ... } }
```

```text
Racer got V8
```

The label `"fast"` describes a **role**, not a class name. You can later replace `V8` with
`ElectricMotor @Qualifier("fast")` and `Racer` doesn't change. That's better than
`@Qualifier("v8")`, which ties the consumer to a bean name.

### 2.6 Custom qualifier annotations: type-safe, typo-proof (verified)

String qualifiers can be mistyped (2.3). Make your own annotation instead:

```java
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Qualifier                                   // ← makes it a qualifier
public @interface Electric {}

@Bean @Electric Vehicle tesla() { ... }
@Bean           Vehicle audi()  { ... }

@Autowired @Electric List<Vehicle> v;
```

```text
@Electric List = [Tesla]
```

A typo like `@Electirc` is now a **compile error** instead of a startup failure. This is the
same meta-annotation trick as custom stereotypes (note 07 §5).

---

## Part 3: `@Qualifier` vs `@Primary`

### Side by side

| | `@Primary` | `@Qualifier` |
|-|------------|--------------|
| Declared on | The **bean** (provider side) | The **injection point** (consumer side); optionally also on beans as a label |
| Meaning | "Use me when nobody says otherwise" | "I need exactly this one" |
| Scope of effect | **Global** default for that type | **Local**: only that one injection point |
| Who decides | The bean's author | The consumer's author |
| When both apply | Loses | **Wins** (verified: BMW over `@Primary` Audi) |
| No match | — | **Fails** (`NoSuchBeanDefinitionException`), no fallback |
| Effect on `List`/`Map` injection | None (all beans) | **Filters** to matching beans |
| How many per type | One | As many labels/requests as you like |
| Typical use | One normal implementation, others special | One consumer needs a specific variant |

### How they work together (the resolution order)

```text
several candidate beans of the type
        │
        ├── @Qualifier at the injection point?  → only matching beans count
        │       └── none match → FAIL (no fallback)
        ├── exactly one @Primary among the rest? → use it
        ├── @Fallback on all but one?            → use the remaining one
        ├── field/param name equals a bean name? → use it
        └── otherwise → NoUniqueBeanDefinitionException
```

### Choosing

| Situation | Use |
|-----------|-----|
| One implementation is the normal choice everywhere | **`@Primary`** on it |
| Most consumers want the default, one needs a variant | **`@Primary`** on the default + **`@Qualifier`** in that one consumer ✅ the classic combination |
| No natural default; every consumer must choose | **`@Qualifier`** everywhere, no `@Primary` |
| A group of beans (e.g. all "electric" ones) | **Qualifier labels** + `@Qualifier` on a `List` |
| Want typo-proof qualifiers | **Custom qualifier annotation** |

Job Finder example (🔜):

```java
@Component @Primary class EmailNotifier implements Notifier { ... }   // default channel
@Component @Qualifier("urgent") class SmsNotifier implements Notifier { ... }

@Service class ApplicationService {
    ApplicationService(Notifier notifier) { ... }                      // → Email (primary)
}
@Service class InterviewReminderService {
    InterviewReminderService(@Qualifier("urgent") Notifier n) { ... }  // → SMS
}
```

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| Typo in `@Qualifier("…")` | `NoSuchBeanDefinitionException` at startup, with no fallback to `@Primary` |
| Two beans with the same qualifier label, single injection | `NoUniqueBeanDefinitionException` |
| Two `@Primary` beans of one type | `NoUniqueBeanDefinitionException` ("more than one 'primary'") |
| Qualifying by a class-like name (`"v8"`) | Consumer coupled to an implementation; prefer role labels (`"fast"`) |
| Expecting `@Primary` to filter a `List` | It doesn't; use a qualifier |
| Using `@Primary` to hide an accidental duplicate bean | Delete the duplicate instead (note 03 §8, Fix 7) |

## Interview questions

**Q: What is `@Primary`?**
It marks a bean as the default candidate when several beans of the same type exist and a
single one is injected by type.

**Q: What is `@Qualifier`?**
It's placed at an injection point (and optionally on beans as a label) to select a specific bean
by qualifier value or bean name.

**Q: `@Qualifier` vs `@Primary`: which wins?**
`@Qualifier`. It's a specific request at the injection point; `@Primary` is only a default.

**Q: What happens if a `@Qualifier` value matches no bean?**
`NoSuchBeanDefinitionException`. There's no fallback to `@Primary`.

**Q: Can several beans share a qualifier?**
Yes. Injecting a `List` with that qualifier gets all of them; injecting a single bean then fails
with `NoUniqueBeanDefinitionException`.

**Q: How do you make qualifiers type-safe?**
Create a custom annotation meta-annotated with `@Qualifier`, e.g. `@Electric`.

**Q: When would you use both together?**
`@Primary` on the usual implementation, and `@Qualifier` in the few places that need a different
one.
