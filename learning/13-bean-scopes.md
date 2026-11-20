# 13 — Bean Scopes in Spring (all six)

## Goal

A bean's **scope** decides **how many instances** Spring creates and **how long each one
lives**. Spring provides **six** scopes: two core ones that work everywhere, and four
web-aware ones. This note explains each, with verified behavior, plus the classic trap of
mixing scopes.

Verified on Spring 7.0.10. The web scopes were simulated with a fake HTTP request (§6),
because this project has no web server yet.

Status: singleton ✅ (every bean in the project so far); the others 🧪.

---

## 1. The six scopes

| # | Scope | Instances | Lives for | Needs |
|---|-------|-----------|-----------|-------|
| 1 | **`singleton`** (default) | **One** per container | The whole application | Core Spring |
| 2 | **`prototype`** | **New one every time** it's requested | Until you drop it (Spring forgets it) | Core Spring |
| 3 | **`request`** | One per **HTTP request** | One request | Web app |
| 4 | **`session`** | One per **HTTP session** (one user's login session) | One user session | Web app |
| 5 | **`application`** | One per **`ServletContext`** (the running web app) | The web app's lifetime | Web app |
| 6 | **`websocket`** | One per **WebSocket session** | One WebSocket connection | Spring WebSocket |

How to declare a scope:

```java
@Component @Scope("prototype") class Proto { }                         // string
@Component @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE) class P { }  // constant (no typos)
@Bean @Scope("prototype") Vehicle vehicle() { ... }                     // on @Bean methods too
@Component @RequestScope class Cart { }       // shortcuts from spring-web:
@Component @SessionScope class Login { }      //   @RequestScope, @SessionScope, @ApplicationScope
```

Constants: `ConfigurableBeanFactory.SCOPE_SINGLETON` / `SCOPE_PROTOTYPE` (core), and
`WebApplicationContext.SCOPE_REQUEST` / `SCOPE_SESSION` / `SCOPE_APPLICATION` (spring-web,
verified: `"request"`, `"session"`, `"application"`).

---

## 2. Scope 1: `singleton` (the default)

Every bean we've written so far is a singleton (note 02 §7).

Verified:

```text
+ created Single#1               ← created at startup (eager), before any getBean
context started
singleton same each time? true
...
closing context...
- destroyed Single#1             ← @PreDestroy runs on close
```

- **One shared instance** for everyone who asks.
- **Eager**: created at startup (fail fast, note 02 §11).
- **Full lifecycle**: init **and** destroy callbacks.

### ⚠️ Singletons are shared between threads

A web server handles many requests **at the same time**, on different threads, all using the
**same** singleton `JobService`. So a singleton must be **stateless**: no fields that change
per request.

```java
@Service class JobService {
    private final JobRepository repo;     // ✅ fine: set once, never changes
    private String currentUserEmail;      // ❌ BUG: request A overwrites request B's value
}
```

Keep per-request data in **method parameters and local variables**, or in a request-scoped
bean (§6).

---

## 3. Scope 2: `prototype`

```java
@Component @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Proto { ... }
```

Verified:

```text
+ created Proto#3
+ created Proto#4
prototype same each time? false (Proto#3, Proto#4)     ← new object per getBean
...
closing context (watch which beans get destroyed)...
- destroyed Single#1                                    ← NO prototype was destroyed
```

- **New instance on every request**: every `getBean` and every injection point.
- `@PostConstruct` runs for **each** new instance.
- ⚠️ **`@PreDestroy` never runs.** Spring hands the object over and **stops tracking it**
  (confirmed above: eight prototypes created, none destroyed). If a prototype holds a
  resource (a file, a connection), **you** must clean it up.
- **Not created at startup.** Only when requested (note that `Proto#2` was created during
  startup only because a singleton needed it, see §4).

**Use for:** stateful, short-lived helper objects, e.g. a builder or a per-task
`JobImportSession` that accumulates results.

---

## 4. ⚠️ The classic trap: a prototype inside a singleton

```java
@Component class Holder {
    private final Proto p;
    Holder(Proto p) { this.p = p; }       // injected ONCE, when Holder is created
    Proto get() { return p; }
}
```

Verified:

```text
plain injection, 2 calls: Proto#2, Proto#2   <- stuck with one
```

The singleton is created once, so it receives **one** prototype instance **once**, and keeps it
forever. The prototype scope is effectively lost. **Scope is applied at injection time, not at
use time.**

### Three fixes (all verified)

**Fix A: `ObjectProvider` (recommended)**

```java
@Component class HolderProvider {
    private final ObjectProvider<Proto> p;
    HolderProvider(ObjectProvider<Proto> p) { this.p = p; }
    Proto get() { return p.getObject(); }          // asks the container every time
}
```

```text
ObjectProvider, 2 calls: Proto#5, Proto#6       ✅
```

**Fix B: `@Lookup` method**

```java
@Component abstract class HolderLookup {
    @Lookup abstract Proto get();                  // Spring implements this method
}
```

```text
@Lookup, 2 calls: Proto#7, Proto#8              ✅
(HolderLookup runtime class: Exp17$HolderLookup$$SpringCGLIB$$0)
```

Spring generates a subclass that overrides `get()` to call `getBean(Proto.class)`.

**Fix C: scoped proxy**

```java
@Component @Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class ProxiedProto { ... }
```

```text
scoped proxy, 2 calls: ProxiedProto#9, ProxiedProto#10      ← ⚠️ new object per METHOD CALL
```

The singleton gets a **proxy**, and every method call on it fetches a **fresh** target.
For prototypes this is usually **surprising**: two calls on "the same" object hit two
different instances, so state set in one call is gone in the next. **Prefer
`ObjectProvider` for prototypes.** Scoped proxies are the right tool for **web scopes**
(§6), where "fresh" means "the current request's instance".

---

## 5. Scopes that need a web environment

This project has only `spring-context`. Verified:

```text
context started fine (nothing asked for the request bean yet)
getBean FAILS: IllegalStateException: No Scope registered for scope name 'request'
```

- Startup **succeeds**, because the error only appears when the bean is first needed.
- `request`, `session` and `application` are **registered by a web application context**
  (Spring MVC / Spring Boot web). `websocket` is registered by Spring's WebSocket messaging
  support.

---

## 6. Scopes 3 & 4: `request` and `session` (simulated, verified)

To try these without a web server, I registered Spring's real `RequestScope` and
`SessionScope` by hand, and simulated requests with a tiny fake `RequestAttributes` class
(an HTTP request plus its session).

```java
@Component @RequestScope class Cart  { ... }    // e.g. data for one HTTP request
@Component @SessionScope class Login { ... }    // e.g. the logged-in user's session data

@Component class Controller {                   // a SINGLETON, like all controllers
    Controller(Cart cart, Login login) { ... }
    String handle() { return "cart=" + cart.id() + " login=" + login.id(); }
}
```

Three requests: Alice twice (same session), then Bob:

```text
Controller is a singleton; cart/login are proxies: Exp17$Cart$$SpringCGLIB$$0
alice req1: cart=11 login=12  (same request again: cart=11 login=12)
alice req2: cart=13 login=12  (same request again: cart=13 login=12)
bob   req3: cart=14 login=15  (same request again: cart=14 login=15)
outside a request: IllegalStateException: No thread-bound request found: Are you referring
to request attributes outside of an actual web request...
```

Reading it:

| Observation | Meaning |
|-------------|---------|
| Within one request, `cart` stays 11 | **Request scope**: one instance per request |
| Alice's req2 gets a new cart (13) | New request → new request-scoped bean |
| Alice keeps `login=12` across req1 and req2 | **Session scope**: one per user session |
| Bob gets his own login (15) | Different session → different instance |
| `Cart` is a `$$SpringCGLIB$$` class | `@RequestScope`/`@SessionScope` **create a proxy automatically** |
| Calling it outside a request fails | There's no "current request" to pick an instance from |

**Why the proxy is essential here:** the singleton `Controller` is created **once**, but it must
use a **different** `Cart` for every request. The proxy is injected once, and on each method call
it looks up the **current** request's `Cart`. Without a proxy you'd hit exactly the §4 trap.

With the plain `@Scope("request")` annotation you must add
`proxyMode = ScopedProxyMode.TARGET_CLASS` yourself. The `@RequestScope` shortcut already does.

**Use for:**
- `request`: per-request context such as a request ID for logging, or the parsed current user.
- `session`: data that follows one user across requests: a shopping cart, a multi-step job
  application form. (Keep it small; sessions live in server memory.)

---

## 7. Scopes 5 & 6: `application` and `websocket`

Not simulated here (they need a real servlet container or WebSocket setup), so this is
summarised from the Spring reference.

**`application`**: one instance per **`ServletContext`**, i.e. per running web application.

- Similar to singleton, but tied to the web app's `ServletContext` and **visible as a
  ServletContext attribute** to other code in the same web app.
- Difference from singleton: a singleton is per **Spring `ApplicationContext`**. One web app can
  contain more than one context (e.g. a parent and a child), but there's one `ServletContext`.
- Use: rarely. App-wide data that must be shared with non-Spring servlet code.

**`websocket`**: one instance per **WebSocket session**.

- Lives as long as one WebSocket connection, e.g. a live chat between a recruiter and a
  candidate.
- Used with Spring's STOMP messaging (`@MessageMapping` controllers). Declared like
  `@Scope(scopeName = "websocket", proxyMode = ScopedProxyMode.TARGET_CLASS)`.

---

## 8. Bonus: custom scopes (verified with `thread`)

Scopes are pluggable. Spring ships a `SimpleThreadScope` that **isn't registered by default**:

```java
ctx.getBeanFactory().registerScope("thread", new SimpleThreadScope());

@Component @Scope("thread") class PerThread { ... }
```

```text
main thread twice: PerThread#16, PerThread#16    ← same within a thread
other thread: PerThread#17                       ← different thread, different instance
```

This is the same mechanism the web scopes use: a `Scope` object that decides where to store
and find instances. (`request` stores them in the current request, `thread` in a `ThreadLocal`.)

---

## 9. Choosing a scope

| Bean | Scope |
|------|-------|
| Services, repositories, controllers, clients, config | **singleton** ✅ (stateless) |
| Stateful helper used once per task | **prototype**, obtained through `ObjectProvider` |
| Data about the current HTTP request | **request** (`@RequestScope`) |
| Data that follows one logged-in user | **session** (`@SessionScope`) |
| App-wide data shared with non-Spring servlet code | **application** (rare) |
| Data for one WebSocket connection | **websocket** |

**Rule of thumb:** ~95% of beans are singletons. If you're tempted to make a service
non-singleton because it has changing fields, **move those fields into method parameters**
instead.

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| Mutable per-request fields in a singleton | Data leaks between concurrent users |
| Injecting a prototype into a singleton directly | Same instance forever (verified) |
| Expecting `@PreDestroy` on a prototype | Never called (verified) |
| Scoped proxy on a prototype | New instance on **every method call** (verified) |
| `@Scope("request")` without a proxy, injected into a singleton | Fails or captures one request's bean; use `@RequestScope` |
| Request/session beans in a non-web context | `No Scope registered for scope name 'request'` (verified) |
| Using a request-scoped bean from a background thread | `No thread-bound request found` (verified) |

## Interview questions

**Q: What scopes does Spring support?**
Six: `singleton` and `prototype` (core), and `request`, `session`, `application`, `websocket`
(web-aware). Custom scopes can be registered too.

**Q: What's the default scope?**
Singleton: one instance per Spring container.

**Q: Singleton vs prototype?**
Singleton: one shared instance, created at startup, full lifecycle. Prototype: a new instance per
request, created on demand, and Spring doesn't call destroy callbacks.

**Q: How do you inject a prototype into a singleton correctly?**
`ObjectProvider<T>`, a `@Lookup` method, or a scoped proxy. Direct injection gives one instance
forever.

**Q: Why do request/session-scoped beans need a proxy?**
They're injected into singletons that are created once. The proxy resolves the **current**
request's or session's instance on each method call.

**Q: Are singleton beans thread-safe?**
Spring doesn't make them thread-safe. Design them stateless (no mutable per-request fields).

**Q: Difference between singleton and application scope?**
Singleton is per Spring `ApplicationContext`. Application is per `ServletContext`, and is also
exposed as a ServletContext attribute.
