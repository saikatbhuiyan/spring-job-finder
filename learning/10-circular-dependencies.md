# 10 — Understanding & Avoiding Circular Dependencies

## Goal

Understand what a circular dependency is, **why** Spring can resolve some cycles and not
others, the workarounds (and why they're workarounds), and above all **how to design it
away**. Builds on [note 08 Part E](08-bean-wiring.md). Everything below was verified on
Spring 7.0.10.

Status: 🧪 learned and verified; not in the project code (and hopefully never will be).

---

## 1. What a circular dependency is

Bean A needs B, and B (directly or indirectly) needs A:

```text
Direct:     A ──▶ B ──▶ A
Indirect:   X ──▶ Y ──▶ Z ──▶ X        (harder to spot: each class looks fine alone)
```

A realistic Job Finder example:

```java
@Service class JobService {
    JobService(ApplicationService apps) { ... }   // closing a job rejects its applications
}
@Service class ApplicationService {
    ApplicationService(JobService jobs) { ... }   // applying checks the job is still open
}
```

Each dependency is reasonable on its own. Together they form a loop. Verified:

```text
BeanCurrentlyInCreationException: Error creating bean with name 'exp14.JobService':
Requested bean is currently in creation: Is there an unresolvable circular reference ...?
```

---

## 2. Why constructor cycles are impossible

To create a bean, Spring must first **finish creating everything its constructor needs**:

```text
create JobService
  └─ needs ApplicationService → create ApplicationService
        └─ needs JobService → "JobService is currently in creation" 💥
```

There's no order that works. Neither object can exist before the other. It's the same as
plain Java: `new A(new B(new A(...)))` never ends.

The 3-bean cycle `X → Y → Z → X` fails the same way (verified).

---

## 3. Why field/setter cycles can "work"

With field or setter injection, creating a bean happens in **two separate steps**:

```text
1. instantiate (call the constructor)   → the object EXISTS, fields still empty
2. populate (inject fields/setters)     → fill in dependencies
```

Between steps 1 and 2, Spring puts an **early reference** to the half-built object into an
internal cache (the *singleton factories / early singleton* cache). So:

```text
create FA: instantiate FA ─▶ expose early FA ─▶ populate FA.b
             └─ needs FB: instantiate FB ─▶ populate FB.a ─▶ finds EARLY FA in cache ✅
           FA.b = finished FB ✅
```

Verified with the default plain Spring settings:

```text
field cycle (plain Spring default)
   FA.b set? true
   OK
```

### …but only under specific conditions (all verified)

| Situation | Result |
|-----------|--------|
| Field cycle, plain Spring defaults | ✅ works |
| Field cycle, **circular references disallowed** (`setAllowCircularReferences(false)`, which is **Spring Boot's default since 2.6**) | ❌ `BeanCurrentlyInCreationException` |
| Field cycle with **`prototype`** scope beans | ❌ fails (no caching of early references for prototypes) |
| **Mixed**: `CtorSide(FieldSide)` constructor + `FieldSide { @Autowired CtorSide }` field, **`FieldSide` created first** | ✅ works |
| The **same** mixed classes, **`CtorSide` created first** | ❌ fails |

The last two rows are the scariest: **the same code works or fails depending on the order
beans happen to be created in**. Renaming a class or moving a package can change that order.
That's why "it works with field injection" is not a solution.

---

## 4. Workarounds that break the cycle at runtime

These make the error go away **without fixing the design**. Know them, but use them only as
a last resort.

### `@Lazy` on one injection point

```java
@Component class LA { LA(@Lazy LB b) { ... } }     // gets a PROXY now, the real LB later
@Component class LB { LB(LA a) { ... } }
```

Verified, even with circular references disallowed:

```text
LA created, got LazyFix$LB$$SpringCGLIB$$0       ← a proxy stands in for LB
LB created (real)
via method: la.b.getA() == la ? true             ← method calls reach the real LB
via FIELD on the proxy: la.b.a = null            ← ⚠️ field access does NOT
```

⚠️ **Proxy trap:** a proxy only **forwards method calls**. Reading a field directly through
it gives the proxy's own (empty) field. Always access dependencies through methods.

### `ObjectProvider<T>`: ask for the bean later

```java
@Component class OA {
    private final ObjectProvider<OB> ob;
    OA(ObjectProvider<OB> ob) { this.ob = ob; }
    void doWork() { ob.getObject().something(); }   // resolved when first used
}
```

Verified: `OA resolves OB later: true`, with circular references disallowed.

### Setter/field injection on one side

Works only under the conditions in §3, and depends on creation order. Not recommended.

---

## 5. ✅ The real fixes: change the design

A cycle usually means **responsibilities are tangled**: two classes each own part of
something that belongs together, or one class does too much.

### Fix 1: Extract the shared logic into a third bean

What `ApplicationService` really needs from `JobService` is "is this job open?". Move that
into its own bean that both can use:

```text
Before:  JobService ⇄ ApplicationService

After:   JobService ──▶ JobLookup ◀── ApplicationService
```

```java
@Component class JobLookup { boolean isOpen(long id) { ... } }
@Component class JobService2 { JobService2(JobLookup l) { ... } }
@Component class ApplicationService2 {
    ApplicationService2(JobLookup l) { ... }
    String apply(long jobId) { return l.isOpen(jobId) ? "applied to job " + jobId : "job " + jobId + " is closed"; }
}
```

Verified with circular references **disallowed**:

```text
applied to job 1 / job 2 is closed
OK
```

### Fix 2: Events, to reverse one direction

`JobService` doesn't actually need to *call* `ApplicationService`. It needs to *announce*
that a job closed. Whoever cares can listen:

```java
public record JobClosed(long jobId) {}

@Service class JobService3 {
    JobService3(ApplicationEventPublisher publisher) { ... }      // no ApplicationService!
    void close(long id) { publisher.publishEvent(new JobClosed(id)); }
}

@Service class ApplicationService3 {
    ApplicationService3(JobService3 jobs) { ... }                 // one direction only
    @EventListener void on(JobClosed e) { /* reject pending applications */ }
}
```

Verified with circular references **disallowed**:

```text
JobService3: closing job 7, publishing event
ApplicationService3: rejecting pending applications for job 7
OK
```

The dependency now goes **one way**, and `JobService` doesn't even know who reacts. Adding an
`EmailService` that also reacts to `JobClosed` needs **no change** to `JobService`.
(`ApplicationEventPublisher` is provided by the `ApplicationContext`; see note 02 §11.)

### Fix 3: Merge or re-split responsibilities

If two classes call each other constantly, maybe they're really **one** concept (merge
them), or there's a missing concept hiding between them (Fix 1). In layered code (note 05),
cycles often appear when two **services at the same level** call each other. A common rule is
that services call repositories and *lower-level* helpers, not each other in both directions.

---

## 6. How to prevent cycles in the first place

1. **Use constructor injection everywhere** (note 08). Cycles then fail **immediately at
   startup** instead of hiding.
2. **Keep circular references disallowed.** Spring Boot does this by default. Never "fix" an
   error by setting `spring.main.allow-circular-references=true`.
3. **Respect layer direction** (note 05): controller → service → repository, never back up.
4. **Watch big constructors.** A class with many dependencies is more likely to be in a cycle.
5. **Read the error.** The bean named in `Requested bean is currently in creation` is part of
   the loop. Follow its constructor parameters to find the rest.

---

## Decision guide

| You see `BeanCurrentlyInCreationException`… | Do this |
|---------------------------------------------|---------|
| Two classes need one small piece of each other | **Extract** that piece into a third bean |
| One side only needs to *notify* the other | **Events** (`ApplicationEventPublisher` + `@EventListener`) |
| Two classes are really one concept | **Merge** them |
| Third-party/legacy code you can't restructure now | `@Lazy` or `ObjectProvider` **as a temporary workaround**, with a TODO |
| ❌ Never | Switch to field injection, or re-enable circular references |

---

## Common mistakes

| Mistake | Why it's bad |
|---------|--------------|
| "Fixing" a cycle by switching to field injection | Hides the design problem; may break when creation order changes |
| Setting `allow-circular-references=true` in Spring Boot | Same; turns a startup error into a fragile setup |
| Accessing fields through a `@Lazy` proxy | Fields read as `null`; only methods are forwarded |
| Ignoring indirect cycles (`X → Y → Z → X`) | Same failure; harder to see, so follow the chain from the error |

## Interview questions

**Q: What is a circular dependency?**
Two or more beans that depend on each other, directly (A ⇄ B) or through a chain (A → B → C → A).

**Q: Why can't Spring resolve constructor-based cycles?**
Each constructor needs a fully created dependency, so neither object can be created first.

**Q: How does Spring resolve setter/field cycles?**
It instantiates a bean, exposes an *early reference* in its singleton cache, and injects that
early reference into the other bean before the first is fully populated.

**Q: Does it work for prototype beans?**
No. Early references are only cached for singletons.

**Q: What does Spring Boot do about circular references?**
Since Boot 2.6 they're disallowed by default (`spring.main.allow-circular-references=false`).

**Q: How do you fix a circular dependency properly?**
Redesign: extract shared logic into a new bean, use events to reverse one direction, or merge
classes. `@Lazy`/`ObjectProvider` are workarounds.

**Q: What does `@Lazy` on an injection point do?**
It injects a proxy; the real bean is resolved on first method call, which breaks the creation
cycle.
