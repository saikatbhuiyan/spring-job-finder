# 33 — Global Exception Handling in Production: the Cases Note 31 Didn't Cover

## Goal

[Note 31](31-global-exception-handling.md) built Job Finder's global exception handling: one
`@RestControllerAdvice`, Problem Details for every error, field-level validation messages, safe 500s.
This note asks: **what happens in production situations that the happy-path tests don't exercise?**

Seven questions were investigated, each by *measuring* the running app first (not by reading docs):

| # | Question | Answer before | Now |
|---|----------|---------------|-----|
| 1 | Does every 500 get logged? | ❌ Spring's own 500s were silent | ✅ every 5xx logged once |
| 2 | What if the failure happens halfway through the response? | ❌ quiet WARN, metrics said "success" | ✅ ERROR + recorded in metrics |
| 3 | What if the client disconnects? | ⚠️ WARN per disconnect (JSON path) | ✅ DEBUG, not an error |
| 4 | Do handled errors still show up in metrics and traces? | ✅ yes (for 5xx) | ✅ now tested |
| 5 | What if a module adds its own `@RestControllerAdvice`? | ❌ silently ignored → 500 | ✅ forbidden by a rule |
| 6 | What language are error messages in? | ❌ whatever `Accept-Language` said | ✅ always English |
| 7 | What about exceptions outside a request (`@Async`, `@Scheduled`)? | ✅ logged by Spring | documented |

Status: ✅ **applied** ([ADR 0008](../docs/adr/0008-exception-handling-hardening.md), extends
[ADR 0006](../docs/adr/0006-global-exception-handling.md)). Boot 4.2.0, Spring Framework 7.1.0,
Micrometer 1.18.

---

## 1. Recap: where every exception goes

```text
controller throws
      │
      ▼
ExceptionHandlerExceptionResolver ──► @ExceptionHandler methods in GlobalExceptionHandler
                                         │  (ours: DomainException, Exception catch-all;
                                         │   inherited: 404, 405, validation, unreadable body...)
                                         ▼
                                  handleExceptionInternal(ex, body, headers, status, request)
                                         │   ◄── every handler ends here: the single exit
                                         ▼
                                  createResponseEntity → JSON problem
```

The key fact for this note: **every** handler in `ResponseEntityExceptionHandler`, and every one of ours,
finishes by calling `handleExceptionInternal`. Overriding that one method lets us enforce a rule for all
of them at once.

## 2. How the questions were investigated: a probe test

When you don't know how a framework behaves, don't guess. Write a **temporary test** that triggers the
situation and *prints* what happens, run it, and read the output:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)      // captures everything logged
class GapProbeTests {
    @Test
    void silent500s(CapturedOutput output) {
        var r = client.get().uri("/api/v1/gap2/not-writable").exchange().returnResult(String.class);
        System.out.println("PROBE -> " + r.getStatus() + " " + r.getResponseBody());
        System.out.println("PROBE logged ERROR=" + output.getOut().contains(" ERROR "));
    }
}
```

The probe is deleted afterwards. What it found becomes a real test with assertions, and the
behaviour gets fixed. Every claim below comes from such a probe.

---

## 3. Question 1: does every 500 get logged?

### What happened

Note 31's catch-all `@ExceptionHandler(Exception.class)` logged unexpected exceptions. But some
exceptions are handled by **inherited** methods of `ResponseEntityExceptionHandler`, which answer
with a 500 and **log nothing**:

| Situation (a bug in *our* code) | Inherited handler | Response | Logged? |
|---|---|---|---|
| Jackson can't serialize the response (a getter throws) | `handleHttpMessageNotWritable` | 500 `"Failed to write request"` | ❌ nothing |
| `@PathVariable("jobId")` but the path says `{id}` | `handleMissingPathVariable` | 500 `"Required path variable 'jobId' is not present."` | ❌ nothing |

So a production bug would produce 500s that **nobody can investigate**: no stack trace, no log line.
The second one also tells the client an internal parameter name, which breaks our rule that "a 500
never reveals internals".

### Why

`ResponseEntityExceptionHandler` maps exceptions to responses; logging is left to you. For 4xx that's
right (a client mistake isn't worth an ERROR). For 5xx it's a trap.

### The fix: one 5xx policy in the single exit

```java
@Override
protected @Nullable ResponseEntity<Object> handleExceptionInternal(
        Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (statusCode.is5xxServerError()) {
        return serverError(ex, headers, statusCode, request);   // log + generic body
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
}

private @Nullable ResponseEntity<Object> serverError(...) {
    ...
    log.error("{} {} failed with {}", servletRequest.getMethod(), servletRequest.getRequestURI(), status.value(), ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, UNEXPECTED_ERROR_DETAIL);
    return super.handleExceptionInternal(ex, problem, headers, status, request);
}
```

Step by step:

1. Any handler that decides on a 5xx status ends up here.
2. We log **once**, at ERROR, with the stack trace (`ex` as the last argument makes SLF4J print it).
3. We **replace** whatever body the inherited handler built with the generic one, so internals never leak.
4. The catch-all becomes a one-liner: it just says "500" and lets the policy do the rest:

```java
@ExceptionHandler(Exception.class)
@Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
}
```

The log line now looks like `GET /api/v1/error-probe/unwritable failed with 500`, followed by the stack
trace. The test checks the line appears and that exactly one ERROR is logged.

> **Design idea:** put a cross-cutting rule at a **choke point** that every path goes through. Adding
> `log.error` to each handler separately would miss the next inherited handler you didn't think of.

---

## 4. Question 2: what if it fails halfway through the response?

### What "committed" means

A response is written in two parts: first the **status line and headers**, then the **body**. Tomcat
buffers output (about 8 KB). When the buffer fills, it is sent to the client. From that moment the
response is **committed**: the status (`200 OK`) is already on the wire and can't be changed.

```text
handler returns 100 000 rows ──► Jackson writes row 1, 2, ... (buffer full → "200 OK" + first 8 KB sent)
                                 ... row 100 001 throws  ──► too late for a 500!
```

### What happened (measured)

| | Before |
|---|---|
| Client | `200 OK` with a **cut-off** JSON body |
| Log | one WARN: `Response already committed. Ignoring: ...` (no stack trace) |
| Metrics | `status=200, outcome=SUCCESS, exception=none`, so it looks like a perfect request |

A bug that corrupts responses would be invisible on dashboards.

### The fix

```java
if (servletResponse != null && servletResponse.isCommitted()) {
    log.error("{} {} failed after the response was committed; the client received an incomplete response",
            servletRequest.getMethod(), servletRequest.getRequestURI(), ex);
    ServerHttpObservationFilter.recordHandledException(servletRequest, ex);
    return null;     // nothing more can be sent
}
```

- **ERROR with stack trace** instead of a quiet WARN.
- `ServerHttpObservationFilter.recordHandledException` attaches the exception to the request's
  **observation** (Spring's measurement of the request, see §6). Metrics then show
  `status=200, exception=HttpMessageNotWritableException`, which you can alert on.
- `return null` from an exception handler means "handled, write nothing".

You can't send a 500 after commit; HTTP has no way to take back a status. Keeping responses small
(pagination, note 25) makes this rare.

---

## 5. Question 3: what if the client disconnects?

Users close tabs, phones lose signal. When the server then writes the response, the operating system
reports `Broken pipe` or `Connection reset`. That's **routine**, not a server bug, and must not page
anyone.

### What happened (measured with a raw socket that sends a request and resets the connection)

Spring Framework 7 wraps the response. When writing to a dead connection fails, the wrapper throws
`AsyncRequestNotUsableException`. The inherited handler for that exception logs one DEBUG line
(`Looks like the client has gone away`) under the logger `...DisconnectedClient`. That's correct.

But when the failure happens **inside Jackson** (the usual case for a JSON API), Jackson wraps it:

```text
HttpMessageNotWritableException            ← what the handler sees
 └─ AsyncRequestNotUsableException: ServletOutputStream failed to write
     └─ IOException: Broken pipe
```

`handleHttpMessageNotWritable` doesn't look inside, so the disconnect became `Response already
committed` (WARN) before our fix, and would have been an ERROR with our new 5xx policy.

### The fix: unwrap, then let Spring handle it as a disconnect

```java
@Override
protected @Nullable ResponseEntity<Object> handleHttpMessageNotWritable(
        HttpMessageNotWritableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    AsyncRequestNotUsableException clientGone = findCause(ex, AsyncRequestNotUsableException.class);
    if (clientGone != null) {
        return handleAsyncRequestNotUsableException(clientGone, request);   // Spring's own: DEBUG
    }
    return super.handleHttpMessageNotWritable(ex, headers, status, request);
}
```

### The tempting shortcut that was rejected

Spring has a helper, `DisconnectedClientHelper.isClientDisconnectedException(ex)`. Why not call it in
`handleExceptionInternal` for every exception? The probe tested it:

| Exception | Helper says "client disconnected"? |
|---|---|
| `IllegalStateException` ← `SocketException("Connection reset")` (e.g. a database) | false ✅ |
| `ResourceAccessException` (RestClient) ← `SocketException("Connection reset")` | false ✅ |
| `IllegalStateException("reading config")` ← `EOFException` | **true** ❌ |

Any `EOFException` anywhere in the cause chain counts as a disconnect. A service failing on a truncated
file would have been answered with an **empty 200** and logged at DEBUG. So the check stays narrow: only a
failed **write of the response**, identified by Spring's own `AsyncRequestNotUsableException`.

> **Lesson:** a helper that classifies exceptions by message or class name is a heuristic. Use it only
> where it can't misfire, and test the cases where it might.

To see disconnect lines when debugging: `logging.level.org.springframework.web.servlet.handler.DisconnectedClient=debug`
(and `...mvc.method.annotation.DisconnectedClient`).

---

## 6. Question 4: do handled errors still appear in metrics and traces?

### Background

Spring Boot Actuator measures every request with **Micrometer**. Spring MVC creates an *observation*
per request; Micrometer turns it into the timer `http.server.requests` with tags like:

```text
uri=/api/{version}/jobs/{id}   method=GET   status=500   outcome=SERVER_ERROR   exception=IllegalStateException
```

(and, once tracing is added, a span marked as failed). A common worry: *if my `@ExceptionHandler`
catches the exception, does monitoring still know about it?*

### What happened (measured)

| Request | `status` | `exception` tag |
|---|---|---|
| Controller throws `IllegalStateException`, our handler answers 500 | 500 | `IllegalStateException` ✅ |
| `ResourceNotFoundException`, our handler answers 404 | 404 | `none` ✅ |
| Validation error, 400 | 400 | `none` ✅ |

Spring Framework 7 does this itself: after an `@ExceptionHandler` ran, `ExceptionHandlerExceptionResolver`
records the exception on the observation **only if the resulting status is 5xx** (found by reading its
bytecode: `recordServerError` checks `is5xxServerError()`). Expected outcomes (404, 409, 400) are not
counted as errors, which is what you want for alerting.

Nothing needed fixing. It is now locked in by a test, because our 5xx policy depends on it. The one
gap was the after-commit case (§4), where the status is 200.

In tests, Boot provides an in-memory `SimpleMeterRegistry`:

```java
@Autowired MeterRegistry meters;

assertThat(meters.find("http.server.requests")
        .tags("uri", "/api/{version}/error-probe/boom", "status", "500", "exception", "IllegalStateException")
        .timer()).isNotNull();
```

The timer is recorded just *after* the response is sent, so the test waits with Awaitility
(`await().untilAsserted(...)`) instead of a fixed `Thread.sleep`.

---

## 7. Question 5: what if a module adds its own `@RestControllerAdvice`?

Imagine the `job` module adds:

```java
@RestControllerAdvice
class JobExceptionHandler {
    @ExceptionHandler(JobClosedException.class)
    ResponseEntity<String> handle() { return ResponseEntity.status(409).body("closed"); }
}
```

### What happened (measured)

The module's handler **never ran**. The client got a **500**, and the global handler logged an ERROR.

### Why: how Spring picks among several advices

Note 31 said "the closest exception type wins". That's true **within one class**. Across advice beans the
rule is different:

```text
for each @ControllerAdvice bean, in @Order order:
    does this bean have ANY @ExceptionHandler that matches the exception?
        yes → use the closest match IN THIS BEAN, stop looking
```

`GlobalExceptionHandler` has `@ExceptionHandler(Exception.class)`, which matches **everything**. Both
beans had the same (default, lowest) order, so bean registration order decided, and the global one came
first. Its catch-all "matched", so the module's more specific handler was never considered.

Adding `@Order(Ordered.HIGHEST_PRECEDENCE)` to the module's advice made it work (418 in the probe). But
ordering several advices with catch-alls is fragile and hard to reason about.

### The decision: exactly one advice

Modules report business failures by throwing `DomainException` subtypes (note 31 §5), which the global
handler maps. A second advice isn't needed, and it's now prevented in two ways:

1. **ArchUnit rule** (our code, clear message at build time):

   ```java
   public static final ArchRule CONTROLLER_ADVICE_ONLY_IN_SHARED_API = classes()
           .that().areMetaAnnotatedWith(ControllerAdvice.class)   // also matches @RestControllerAdvice
           .should().resideInAPackage("..shared.api..")
           .allowEmptyShould(true)
           .because("a module's own advice would be shadowed by the global handler; ...");
   ```

   `areMetaAnnotatedWith` matters: `@RestControllerAdvice` is itself annotated with `@ControllerAdvice`.

2. **Context test** (also catches an advice brought in by a **library**):

   ```java
   assertThat(context.getBeansWithAnnotation(ControllerAdvice.class).values())
           .singleElement().isInstanceOf(GlobalExceptionHandler.class);
   ```

Verified by adding a stray advice to production code: both checks failed, and ArchUnit named the class.

---

## 8. Question 6: what language are the error messages in?

### Background: the request locale

For every request, Spring MVC asks a `LocaleResolver` for the request's `Locale` and stores it in
`LocaleContextHolder`. Boot's default resolver reads the **`Accept-Language`** header, which browsers send
automatically (`de-DE,de;q=0.9,en;q=0.8`). Hibernate Validator's messages ("must not be blank") are
translated into many languages, and Spring's validator uses the request locale to pick one.

### What happened (measured)

```json
// Accept-Language: de
{"title":"Invalid request", "detail":"The request contains invalid fields or parameters.",
 "errors":[{"pointer":"#/title","detail":"darf nicht leer sein"}]}
```

English title, English detail, German field message: **two languages in one response**. French clients got
`ne doit pas être vide`. Note 31 / ADR 0006 assumed the messages were English, which was wrong.

### Options

| Option | Effort | Result |
|---|---|---|
| Fully localize (`messages_de.properties` for every title/detail, `Content-Language`, `Vary: Accept-Language`) | high, forever | correct for any language |
| **Fix the locale to English** | two properties | one language, independent of client and server |

For an API, error text is read by **developers and logs**. Frontends that show messages to users should
translate from the machine-readable parts (`type`, `pointer`; a per-violation `code` is a follow-up in
ADR 0008). Job Finder fixes the locale:

```properties
spring.web.locale=en
spring.web.locale-resolver=fixed
```

Verified:

- `Accept-Language: de-DE` → `"must not be blank"`.
- The same test on a **German JVM** (`-Duser.language=de`) still returns English. Without the properties,
  that JVM answered `darf nicht leer sein` even when the client sent no header.
- `fixed` **without** `spring.web.locale` doesn't fall back to the JVM locale: Boot refuses to start
  (`'locale' must not be null`). A safe failure, but both lines are needed.

---

## 9. Question 7: exceptions outside a request

`@RestControllerAdvice` only sees exceptions thrown while Spring MVC handles a **request**. Background work
runs on other threads. Measured with `@EnableAsync` / `@EnableScheduling` in a probe:

| Code | Who handles the exception | Logged? |
|---|---|---|
| `@Async void send()` throws | `SimpleAsyncUncaughtExceptionHandler` | ✅ ERROR `Unexpected exception occurred invoking async method` |
| `@Async CompletableFuture<T> load()` throws | the future completes exceptionally | ❌ **nobody**, unless the caller checks the future |
| `@Scheduled(fixedDelay=...)` throws | `TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER` | ✅ ERROR each run, and **the task keeps running** |

Job Finder has no async or scheduled work yet, so no code was added (configuration for unused features
is clutter). Rules for when it arrives:

- Never "fire and forget" a `CompletableFuture`; whoever calls it must handle `exceptionally`/`whenComplete`.
- Customize with `AsyncConfigurer.getAsyncUncaughtExceptionHandler()` only if the default ERROR log isn't
  enough (e.g. to add context or metrics).

---

## 10. The final picture

```text
exception in a controller
   │
   ├─ DomainException ───────────────► 404 / 409, client-safe message           (note 31)
   ├─ validation / unreadable body ──► 400 validation-error with pointers       (note 31)
   ├─ write failed, client gone ─────► nothing sent, DEBUG                      (§5)
   └─ anything else / inherited ─────► handleExceptionInternal
                                          ├─ 4xx ─► standard Problem Details
                                          └─ 5xx ─► response committed?
                                                     ├─ no  ─► ERROR log + generic 500   (§3)
                                                     └─ yes ─► ERROR log + recorded in metrics (§4)

exception in a filter / container ─► /error ─► ProblemDetailsErrorController (note 31 §8)
exception in @Async / @Scheduled  ─► Spring's default ERROR log (§9)
```

## 11. Verification summary

| Check | Result |
|-------|--------|
| `GlobalExceptionHandlingTests`: 6 new tests (inherited 500s ×2, after-commit, client disconnect over a raw socket, metrics, `Accept-Language`) and a stricter single-advice check | ✅ 20 tests |
| `ArchitectureRulesTests`: new rule rejects the `JobExceptionHandler` fixture | ✅ |
| Full suite `mvn verify` | **77 tests, 0 failures** (was 69) |
| Timing-sensitive tests (disconnect, after-commit, metrics) repeated 5× | 5/5 passed |
| Mutation: remove the 5xx policy | 3 tests fail (inherited 500s, after-commit, unexpected 500) |
| Mutation: remove the disconnect unwrap | the disconnect test fails (ERROR logged) |
| Mutation: remove `recordHandledException` | the after-commit test fails (metrics show no exception) |
| Mutation: remove the locale properties | the `Accept-Language` test fails (`darf nicht leer sein`) |
| Mutation: add a second advice in production code | ArchUnit rule and context test both fail |
| Packaged jar, `prod` profile, German JVM | starts; 404 is English `application/problem+json`; no ERROR logs |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Logging only in your own catch-all | Spring's inherited handlers return silent 500s (verified, §3) |
| Trusting the inherited 500 `detail` | Internal names reach the client (`'jobId'`) |
| Treating `Response already committed` as harmless | Truncated responses, invisible in metrics (§4) |
| Logging client disconnects at WARN/ERROR | Alert noise from every closed tab |
| Classifying disconnects with a broad heuristic | Real failures hidden as empty 200s (§5) |
| A module-level `@RestControllerAdvice` next to a global catch-all | The module's handler silently never runs (§7) |
| Assuming validation messages are English | `Accept-Language` or the server OS changes them (§8) |
| `spring.web.locale-resolver=fixed` without `spring.web.locale` | App doesn't start |
| Fire-and-forget `@Async` futures | Failures logged by nobody (§9) |
| `Thread.sleep` in tests that wait for logs or metrics | Slow and flaky; use Awaitility |

## Interview questions

**Q: You have two `@ControllerAdvice` classes. Which handler runs?**
Spring goes through the advice beans in `@Order` order and uses the first bean that has *any* matching
`@ExceptionHandler`; within that bean, the closest exception type wins. A catch-all `Exception` handler
in an earlier bean therefore shadows specific handlers in later ones.

**Q: Does `ResponseEntityExceptionHandler` log exceptions?**
No. It maps them to responses. Some mappings are 500s (unwritable response, missing path variable), so
you need your own logging for 5xx, ideally in `handleExceptionInternal`, which every handler calls.

**Q: What does "response already committed" mean, and what can you do?**
The status and headers (and part of the body) were already sent, so you can't change the status. Log the
failure as an error, record it for monitoring, and keep responses small (pagination) so it's rare.

**Q: How do you avoid alert noise from clients that disconnect?**
Recognize the disconnect precisely (Spring's `AsyncRequestNotUsableException`) and log it at DEBUG. Don't
use broad heuristics that could hide real failures.

**Q: If an `@ExceptionHandler` handles an exception, do metrics still see it?**
In Spring Framework 6.1+ / 7, yes for 5xx responses: the resolver records the handled exception on the
request's observation, so `http.server.requests` gets an `exception` tag and traces mark the span as an
error. 4xx outcomes are deliberately not recorded as errors.

**Q: Why might validation messages come back in German?**
The request locale follows `Accept-Language` by default, and Bean Validation messages are localized. Either
localize everything consistently or fix the locale (`spring.web.locale` + `spring.web.locale-resolver=fixed`).

**Q: Does `@RestControllerAdvice` catch exceptions from `@Async` or `@Scheduled` methods?**
No. They run outside a request. `@Async void` failures go to the `AsyncUncaughtExceptionHandler`,
`@Async` futures carry the failure to the caller, and `@Scheduled` failures are logged by the scheduler's
error handler.
