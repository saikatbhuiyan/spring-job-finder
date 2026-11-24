# 38 — Request Correlation IDs in Spring Boot: From `X-Request-Id` to W3C Trace Context

## Goal

A user writes: *"I got an error at 14:02 when I opened job 42."* At 14:02 the server handled 300 requests on
40 threads, and their log lines are interleaved. Which lines belong to **that** request?

A **correlation ID** is a unique ID given to each request and written into **every log line** the request
produces, so you can filter the logs down to exactly one request. If the client also receives the ID, the
user can quote it: *"error, trace ID `4bf92f35…`"*, and support finds every related line in seconds.

This note:

1. builds the classic hand-rolled version (a filter + the logging MDC) step by step, and **measures** what
   goes wrong with it;
2. shows why the industry replaced "request IDs" with **W3C trace IDs**, and how Spring Boot 4 provides them
   with almost no code;
3. finds, by experiment, the places where Boot's defaults are **not** production-safe, and fixes each;
4. ends with **what is actually used and safe**, and how Job Finder does it.

Status: ✅ **applied** ([ADR 0013](../docs/adr/0013-request-correlation.md)). Experiments: a scratch Spring
Boot 4.2 app (`exp35`) run in several configurations, plus Job Finder's tests.

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **correlation ID** | any ID that ties together the log lines (and other signals) of one unit of work |
| **request ID** | a correlation ID for one HTTP request, often sent in `X-Request-Id` |
| **trace** | one end-to-end operation, possibly spanning several services; identified by a **trace ID** (32 hex chars) |
| **span** | one step inside a trace (the HTTP request in this service, one database call, one outgoing call); **span ID** (16 hex chars) |
| **MDC** | *Mapped Diagnostic Context*: a per-thread map that the logging framework can print in every line |
| **propagation** | passing the trace ID on: to other threads, and to other services in request headers |
| **`traceparent`** | the W3C standard header that carries the trace ID between services |
| **baggage** | extra key-value pairs propagated alongside the trace (W3C `baggage` header) |
| **sampling** | choosing which traces are **exported** to a tracing backend (e.g. 10 %) |
| **exporter** | the component that sends spans to a backend (Jaeger, Tempo, Zipkin, an OpenTelemetry Collector) |

---

## 2. Step 1: the MDC, the logging framework's per-request notebook

SLF4J (the logging API Spring Boot uses, with Logback behind it) has a class `MDC`. It is a
`ThreadLocal<Map<String, String>>`: every thread has its own map.

```java
MDC.put("requestId", "abc-123");     // stored for the CURRENT thread only
log.info("Loading job 42");          // the pattern %X{requestId} prints "abc-123"
MDC.remove("requestId");             // must be cleaned up: the thread is reused!
```

In a log pattern, `%X{requestId}` prints the value, `%X{requestId:-}` prints nothing when it's missing.

Why this works for web requests: Tomcat handles each request **on one thread from start to end** (unless
you go async). So "put the ID into the MDC when the request starts, remove it when it ends" tags every line
logged in between.

Why it's fragile: Tomcat threads are **pooled**. A thread that served Alice's request serves Bob's next. If
the MDC isn't cleared, Bob's lines carry Alice's ID.

---

## 3. Step 2: the hand-rolled filter (the tutorial version)

```java
@Component
class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");            // reuse the caller's ID...
        if (id == null) {
            id = UUID.randomUUID().toString();                // ...or make one
        }
        MDC.put("requestId", id);
        res.setHeader("X-Request-Id", id);                    // give it to the client
        chain.doFilter(req, res);
        MDC.remove("requestId");                              // BUG: skipped if the chain throws
    }
}
```

It looks fine. I ran it in the scratch app with **one Tomcat thread** (`server.tomcat.threads.max=1`) to make
thread reuse deterministic, and measured four problems.

### Problem 1: IDs leak between requests (measured)

A variant whose cleanup didn't run (exactly what happens above when the chain throws):

| Request | Sent `X-Request-Id` | Response `X-Request-Id` | MDC during the request |
|---|---|---|---|
| Alice | `alice-req-1` | `alice-req-1` | `requestId=alice-req-1` |
| anonymous | (none) | **`alice-req-1`** | **`requestId=alice-req-1`** |

The anonymous request was logged, and answered, as Alice's. With `try { chain.doFilter } finally {
MDC.remove(...) }` the second request got a fresh UUID. **Rule: MDC cleanup always goes in `finally`.**

### Problem 2: the client controls what's written into your logs (measured)

| Sent `X-Request-Id` | Result |
|---|---|
| 7 000 × `A` | accepted (200); 7 KB in every log line of that request |
| `x] INFO  c.s.j.AuthService : admin login OK [` | accepted (200); in a plain-text log it looks like a real log line |
| a value with an ESC control character | **400**: Tomcat rejects control characters in headers |

So a client-supplied ID must be validated (length, allowed characters) or replaced.

### Problem 3: other threads don't see it (measured)

```java
CompletableFuture.supplyAsync(() -> { log.info("plain executor"); ... }, plainExecutor);
applicationTaskExecutor.execute(() -> log.info("boot applicationTaskExecutor"));
```

Both lines were logged with an **empty MDC**: `ThreadLocal` values don't travel to pool threads. Every
`@Async` method, every event listener on an executor, every `CompletableFuture` loses the ID.

### Problem 4: it stops at the service boundary

The ID doesn't reach the next service unless every outgoing HTTP client, message producer and consumer
copies the header, and every team agrees on the header name (`X-Request-Id`? `X-Correlation-Id`?).

All four are solvable by hand, but that is a lot of code to get exactly right in every service. That's
why the industry standardized it.

---

## 4. Step 3: from request IDs to traces (W3C Trace Context)

Distributed tracing systems (Zipkin, Jaeger, and now **OpenTelemetry**, the standard) needed the same thing
plus more: one ID for the whole operation across services, and an ID per step. The W3C standardized the
header in 2020: **Trace Context**.

```
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
             ││ └──────────── trace ID ──────────┘ └─ parent span ─┘ └ flags (01 = sampled)
             └ version
```

- The **trace ID** is the correlation ID: the same in every service the operation touches.
- Each service creates its own **span** (new span ID) inside that trace.
- Every service with tracing reads `traceparent` on the way in and writes it on the way out.

So "correlation ID" today means **the trace ID**. One ID, one standard, and every tracing tool, log
platform and cloud load balancer understands it.

---

## 5. Step 4: Spring Boot's built-in tracing

Spring Framework already **observes** every request: `ServerHttpObservationFilter` wraps each request in a
Micrometer `Observation` (that's where the `http.server.requests` metric comes from). With **Micrometer
Tracing** on the classpath, each observation also becomes a **span**, and Micrometer puts `traceId` and
`spanId` into the **MDC** while it's open. Spring Boot's default log pattern prints them.

### Which dependencies? (measured)

Boot 4 has a starter, `spring-boot-starter-opentelemetry`. Its dependency tree includes OTLP **exporters**
(with OkHttp and the Kotlin standard library) and a metrics registry. Running it with **no configuration**:

```
PushMeterRegistry : Publishing metrics for OtlpMeterRegistry every 1m to http://localhost:4318/v1/metrics
```

and a minute later the app had an open TCP connection to port 4318. On this machine an OpenTelemetry
Collector container (from another project) happened to listen there, and **received the scratch app's
metrics**. In production that's either a stream of warnings when nothing listens, or data sent to whatever
does.

Without a backend to export to, the right choice is the tracing modules **without** exporters:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

Measured: no connection to 4318, no OkHttp, no Kotlin; 16 jars, 1.35 MB (OpenTelemetry API + SDK,
Micrometer Tracing, context propagation).

### What you get (measured, 10 requests, default sampling 0.1)

```
INFO 89530 --- [exp35] [io-18080-exec-2] [a9b1950e743f510f2ea242321b36cbdb-019ca8c74a54c97d] exp.App$Probe : handling hello
```

| | Result |
|---|---|
| requests with a trace ID in the MDC | **10 / 10** |
| requests sampled (`sampled=true`) | 1 / 10 |
| response header with the ID | **none** |

Important: **sampling doesn't affect logging.** Every request gets a trace ID; sampling only decides
which spans an exporter would send to a backend. So a low sampling rate is fine for correlation.

---

## 6. Step 5: what the server accepts from callers (measured)

| Incoming header | Boot default | After `propagation.type=w3c`, baggage off |
|---|---|---|
| valid `traceparent` | trace continued (same trace ID) | same |
| `traceparent` with `-01` (sampled) | **sampled**, whatever our sampling rate | same (see §10) |
| non-hex / all-zero ID / trailing text | ignored, new trace | same |
| `b3: 80f198ee…-e457…-1` (Zipkin B3) | **accepted** | ignored |
| `baggage: user.role=admin,secret=hunter2` | **forwarded verbatim** on outgoing calls | not forwarded |

The `traceparent` parser is strict, so it can't be used to inject text into logs (unlike Problem 2).

Two defaults are wider than needed:

- **B3 headers** are an older Zipkin format; accepting them adds input formats nobody here sends.
- **Baggage** is the dangerous one. A downstream service that reads `user.role` from baggage would trust a
  value the **client** chose. Nothing in Job Finder uses baggage, so it's turned off:

```properties
management.tracing.propagation.type=w3c
management.tracing.baggage.enabled=false
```

---

## 7. Step 6: give the ID to the client

Boot doesn't send the trace ID back. Without it, the user has nothing to quote. Two places:

1. **A response header on every response**: `X-Trace-Id: 0af7651916cd43dd8448eb211c80319c`. Frontend code
   can read it (once CORS exposes it) and show it in an error dialog.
2. **The Problem Details body of every error**: `"traceId": "0af76519…"`. That's what people paste into bug
   reports, and what you see in curl or Postman. (ASP.NET Core does exactly this.)

```json
{
  "title": "Internal Server Error",
  "status": 500,
  "detail": "An unexpected error occurred.",
  "instance": "/api/v1/jobs/42",
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

Is it safe to show? The trace ID is a random number: it reveals nothing and grants nothing. It must just
never be **used** for anything but correlation (not as a session or idempotency key).

Header name: there is no final standard yet. W3C Trace Context Level 2 proposes a `traceresponse` header,
but it isn't a Recommendation. `X-Trace-Id` is the common convention (`X-Request-Id` would suggest we echo
a client's request ID, which we don't).

### The filter

It must run **inside** the observation (so the span exists) and **before** anything that can answer the
request (the rate limit's 429, Spring Security, Spring MVC):

```
CorsFilter (HIGHEST) → ServerHttpObservationFilter (HIGHEST+1, starts the trace)
  → TraceIdFilter (HIGHEST+2) → RateLimitFilter (HIGHEST+10) → … → DispatcherServlet
```

```java
Span span = tracer.currentSpan();
if (span != null) {
    String traceId = span.context().traceId();
    request.setAttribute(ATTRIBUTE, traceId);       // for Problem Details writers (see §8)
    response.setHeader("X-Trace-Id", traceId);
}
chain.doFilter(request, response);
```

Measured: a header set here **survives** `response.sendError(401)` and an exception thrown later in the
chain. Both still went out with `X-Trace-Id`.

---

## 8. Step 7: where the trace is lost, and the fixes

### Async threads (measured)

| Where `log.info` runs | MDC `traceId` |
|---|---|
| request thread | ✅ |
| plain `Executors.newFixedThreadPool` | ❌ empty |
| Boot's `applicationTaskExecutor` (default) | ❌ empty |
| Boot's `applicationTaskExecutor` + `ContextPropagatingTaskDecorator` bean | ✅ same trace ID |

```java
@Bean
ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
    return new ContextPropagatingTaskDecorator();
}
```

Boot applies a `TaskDecorator` bean to the executors it auto-configures. The decorator takes a **snapshot**
of the current context (the observation, and with it the trace) when a task is submitted, and restores it
on the worker thread. Job Finder has no async code yet, but Spring Modulith's `@ApplicationModuleListener`
(planned) runs on that executor, so the decorator is in place before the first listener. A hand-made
executor still needs it set explicitly.

### Errors thrown by filters (measured)

The trace's scope is opened and closed by `ServerHttpObservationFilter`. When a **filter** after it
throws, the exception passes through the observation filter (which records it and **closes the
scope**), and only then does Tomcat log it:

```
ERROR … [                                                 ] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() … threw exception
```

Blank brackets: **no trace ID** on the stack trace, the one line the client's ID should find. Then Tomcat
forwards to `/error` (the *error dispatch*), which also runs with an empty MDC.

Fix: `TraceIdFilter` already wraps the rest of the chain, inside the trace. It catches exceptions there,
logs them once **with** the trace ID, records them for metrics (so `http.server.requests` still shows the
exception), and calls `sendError(500)` so the error controller still answers with Problem Details:

```java
try {
    chain.doFilter(request, response);
} catch (ServletException | RuntimeException ex) {
    ServerHttpObservationFilter.recordHandledException(request, unwrap(ex));
    log.error("{} {} failed with 500", request.getMethod(), request.getRequestURI(), ex);
    response.sendError(500);       // (if not committed; otherwise only logged)
}
```

`IOException`s aren't caught: they're mostly clients that disconnected, which Tomcat already treats quietly.

The error controller runs after the scope closed, so it can't ask the tracer. That's why the filter also
stores the ID as a **request attribute**: the error dispatch reuses the same request object, so the
controller reads the attribute.

---

## 9. Step 8: the ID in log files, per environment

**Default pattern** (no profile, tests): Boot adds `[traceId-spanId]` automatically.

**Dev** (Job Finder's custom compact pattern): a custom `logging.pattern.console` replaces Boot's, so
the ID disappears unless the pattern prints it. Job Finder prints the trace ID only (the span ID is noise
without a trace viewer):

```properties
logging.pattern.console=… %clr([%-15.15t]){faint} %clr(%-32.32X{traceId:-}){magenta} %clr(%-36.36logger{35}){cyan} …
```
```
22:15:52.313 DEBUG [io-8080-exec-2 ] 40fafe72c3d82da42e4d5c1765853819 c.s.j.shared.api.RateLimitFilter : Rate limit exceeded …
```

**Prod** (ECS JSON): Boot writes MDC entries as top-level members named after the MDC keys:

```json
{"message":"handling hello","traceId":"7ce41748…","spanId":"5f0031f9…", …}
```

The **Elastic Common Schema** names are `trace.id` and `span.id`. Elastic (and other ECS consumers) join
logs to traces on those fields. Boot's structured logging can rename members:

```properties
logging.structured.json.rename.traceId=trace.id
logging.structured.json.rename.spanId=span.id
```
```json
{"message":"handling hello","trace.id":"c5b7aa7c…","span.id":"13398dbb…", …}
```

(A dotted key is how Elastic's own ECS loggers write it; Elasticsearch treats `"trace.id"` and
`{"trace":{"id":…}}` the same.)

---

## 10. Step 9: trust: who chooses the trace ID?

If any client may send `traceparent`, then any client:

- **chooses our trace ID**, and could make its requests look related to someone else's (only if it
  knows that ID);
- **chooses the sampling decision** (`-01` forced sampling in the experiment). Today nothing is exported,
  so this costs nothing. With an exporter, a client could force 100 % sampling and multiply tracing costs.

We do want to continue traces from **our own** gateway, frontend or other services. The standard answer
is the same as for `X-Forwarded-For` (note 37 §12): **the edge decides**. The public-facing proxy or
gateway strips (or replaces) `traceparent` on requests from the internet, and the application trusts what
reaches it. When exporting starts, the sampler should also not let untrusted callers force sampling
(parent-based sampling only for trusted callers, or tail sampling in the collector).

---

## 11. The approaches side by side

| Approach | Pros | Cons |
|---|---|---|
| Hand-rolled `X-Request-Id` filter + MDC | tiny; no dependencies; easy to understand | every trap in §3 is yours to handle; stops at the service; non-standard header names |
| Spring Cloud Sleuth | was the Spring standard | **gone**: replaced by Micrometer Tracing in Spring Boot 3 |
| **Micrometer Tracing + OpenTelemetry bridge** (Boot auto-configuration) | standard W3C IDs; MDC, outgoing `RestClient`/`WebClient`/messaging propagation, metrics/trace integration; exporter is one dependency away | a span per request (cheap); defaults to review (B3, baggage, starter exporters) |
| OpenTelemetry **Java agent** (`-javaagent`) | zero code; instruments many libraries | a bytecode agent in production; behaviour changes with agent versions; harder to test in unit tests |
| Gateway-only request ID (nginx `$request_id`, Envoy `x-request-id`) | free at the edge | the app still has to put it in the MDC and propagate it: back to the hand-rolled filter |

---

## 12. What is actually used, and what's safe

**Used:** the **W3C trace ID as the correlation ID**, via OpenTelemetry (in Spring: Micrometer Tracing with
the OTel bridge, or the OTel Java agent). Request-ID headers survive mostly at the edge.

**Safe** means all of these, not just "a filter that sets the MDC":

1. One ID per request, generated server-side or taken **only** from a valid W3C `traceparent`; the edge
   strips it from untrusted callers.
2. Context **cleared** after the request (the tracing library's scopes do it; hand-written code needs
   `finally`).
3. Context **propagated** to async work (`ContextPropagatingTaskDecorator`) and to outgoing calls
   (instrumented clients: use Boot's `RestClient.Builder`, not `RestClient.create()`).
4. The ID **returned to the client** (header + error body), so users can quote it.
5. **Every** log line of the request has it, including errors thrown by filters.
6. Logs in the format the log platform joins on (`trace.id` for ECS).
7. No accidental data flows: no unsolicited exporters, no client baggage forwarded.

---

## 13. Job Finder's implementation

| What | Where |
|---|---|
| Dependencies (no exporter) | `pom.xml`: `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel` |
| W3C only, no baggage | `application.properties` |
| `X-Trace-Id` + request attribute + filter-exception logging | `shared/api/TraceIdFilter` (order `HIGHEST_PRECEDENCE + 2`) |
| Registration + async context propagation | `shared/api/TracingConfig` |
| `traceId` in Problem Details | `GlobalExceptionHandler.createResponseEntity`, `ProblemDetailsErrorController`, `RateLimitFilter.reject` |
| `X-Trace-Id` CORS-exposed | `CorsConfig.EXPOSED_HEADERS` |
| Dev log pattern with the ID | `application-dev.properties` |
| ECS `trace.id` / `span.id` | `application-prod.properties` |

### Tests

| Test | Locks in |
|---|---|
| `TracingTests` (11) | header on every response (API and actuator), different per request, equal to the ID in the request's log line; caller's valid `traceparent` continued; 4 invalid ones → new trace; B3 ignored; only `traceparent` passed downstream (no baggage); `traceId` in a Spring MVC problem; async task keeps the trace |
| `GlobalExceptionHandlingTests` | filter exception: one ERROR line, **with** the trace ID; `traceId` in the 500 body; exception still in metrics (`uri=UNKNOWN`, `exception=IllegalStateException`); a `ServletException`-wrapped one recorded under its cause |
| `RateLimitTests` | the 429 has `X-Trace-Id` and `traceId` |
| `CorsTests` | `X-Trace-Id` is exposed |
| `ProfileConfigurationTests` | in a child JVM: prod JSON line of a request has `trace.id` = header and no `traceId`; dev line shows the ID |

### Mutation checks

Each safety property was removed in turn; each time, the test guarding it failed:

| Mutation | Failing test |
|---|---|
| accept B3 again | `TracingTests.b3HeadersAreIgnored` |
| baggage on | `TracingTests.onlyTraceparentIsPassedOnToDownstreamCallsNeverTheClientsBaggage` |
| no `ContextPropagatingTaskDecorator` | `TracingTests.asyncWorkOnTheApplicationTaskExecutorStaysInTheTrace` |
| let the container log filter exceptions | `GlobalExceptionHandlingTests.exceptionInAFilterIsA500ProblemDetailWithoutTheMessage` |
| filter ordered after the rate limit | `RateLimitTests.requestsOverTheLimitAre429WithRetryAfterAndNeverReachTheController` |
| no `traceId` from the error controller | `GlobalExceptionHandlingTests.exceptionInAFilter…` |
| no `traceId` from `GlobalExceptionHandler` | `TracingTests.problemDetailsCarryTheTraceId` |
| exception not recorded for metrics | `GlobalExceptionHandlingTests.exceptionInAFilter…` |
| `ServletException` wrapper recorded instead of its cause | `GlobalExceptionHandlingTests.aWrappedFilterExceptionIsRecordedUnderItsCause` |
| no ECS rename | `ProfileConfigurationTests.prodLogLinesOfARequestCarryItsTraceIdUnderTheEcsName` |
| dev pattern without the ID | `ProfileConfigurationTests` (dev tests) |
| `X-Trace-Id` not CORS-exposed | `CorsTests` |

### Found along the way

- **A metric assertion that passed for the wrong reason.** The first version checked "some 500 with
  `exception=IllegalStateException` exists". Removing the recording code didn't fail it: another test in the
  same Spring context had produced such a timer. Narrowed to the request's own tags (`uri=UNKNOWN`), the
  mutation failed it, as it must. Lesson: a guard test is only proven by the mutation check.
- **A flaky test caused by the trace ID.** `ValidationTests` checked that a rejected value `abc` is never
  echoed, by searching the whole body. Every body now contains a random hex `traceId`, and one run's ID was
  `10d49c85582cb946231d39abc8ad26c6`. The probe values became `xyz` (letters that are never hex), keeping
  the check exactly as strict.

---

## 14. Run it / verify it (real output)

**Dev** (`mvn spring-boot:run`):

```bash
curl -i localhost:8080/api/v1/jobs/42
```
```
HTTP/1.1 404
X-Trace-Id: f6de9ea4d1630e3adf0f5b5c91d77c88
Content-Type: application/problem+json

{"detail":"No static resource api/v1/jobs/42.","instance":"/api/v1/jobs/42","status":404,"title":"Not Found","traceId":"f6de9ea4d1630e3adf0f5b5c91d77c88"}
```

```bash
curl -i -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' localhost:8080/api/v1/jobs
#  X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736              (the caller's trace, continued)
curl -i -H 'b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1' localhost:8080/api/v1/jobs
#  X-Trace-Id: c23e6e0e1adb4b7d02584e43ee381199              (B3 ignored: a new trace)
curl -i -H 'Origin: http://localhost:5173' localhost:8080/api/v1/jobs
#  Access-Control-Expose-Headers: Location, Deprecation, Sunset, Link, Retry-After, X-Trace-Id
```

Console (the startup lines have blanks where the ID goes; each request line has its ID):

```
22:29:20.258  INFO [restartedMain  ]                                  c.s.jobfinder.JobFinderApplication   : Started JobFinderApplication in 1.128 seconds
22:29:20.491  WARN [nio-8080-exec-2] f6de9ea4d1630e3adf0f5b5c91d77c88 .a.ExceptionHandlerExceptionResolver : Resolved [...NoResourceFoundException: No static resource api/v1/jobs/42 ...]
22:29:20.508  WARN [nio-8080-exec-4] 4bf92f3577b34da6a3ce929d0e0e4736 .a.ExceptionHandlerExceptionResolver : Resolved [...]
```

**Prod** (the packaged jar, a limit of 1 request and the rate limit's DEBUG line, to have a log line inside
a request):

```bash
mvn clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar \
  --jobfinder.rate-limit.requests=1 --logging.level.com.shahabuddin.jobfinder.shared.api.RateLimitFilter=debug
curl -s localhost:8080/api/v1/jobs > /dev/null; curl -i localhost:8080/api/v1/jobs
```
```
HTTP/1.1 429
X-Trace-Id: b103b773de18d2b389a996e3b175cbf3
Retry-After: 60

{"detail":"Too many requests. Retry after 60 seconds.","instance":"/api/v1/jobs","status":429,"title":"Too Many Requests","traceId":"b103b773de18d2b389a996e3b175cbf3"}
```
```json
{"@timestamp":"2026-11-24T16:31:07.868599Z","log":{"level":"DEBUG","logger":"com.shahabuddin.jobfinder.shared.api.RateLimitFilter"},…,"message":"Rate limit exceeded for 0:0:0:0:0:0:0:1 on GET /api/v1/jobs; retry after 60 s","trace.id":"b103b773de18d2b389a996e3b175cbf3","span.id":"62d683540d14d0fa","ecs":{"version":"8.11"}}
```

`lsof -a -p <pid> -iTCP` on the running prod app: only its own port 8080, no connection to 4318.

Tests: `mvn clean verify`: **164 tests, 0 failures** (150 before; 14 new).

---

## Common mistakes

| Mistake | Consequence |
|---|---|
| `MDC.remove` not in `finally` | the next request on that thread logs with the previous request's ID |
| Trusting any `X-Request-Id` | unbounded or fake-looking text in logs |
| Custom log pattern without `%X{traceId}` | the ID exists but is never printed |
| Thinking sampling limits correlation | it doesn't: every request has a trace ID; sampling only limits export |
| `@Async` / own executors without context propagation | async log lines lose the ID |
| `RestClient.create()` instead of Boot's `RestClient.Builder` | outgoing calls aren't instrumented: no `traceparent` downstream |
| Adding `spring-boot-starter-opentelemetry` "just for IDs" | OTLP exporters active: pushes to `localhost:4318` |
| Leaving baggage on without using it | clients' arbitrary key-values forwarded to every downstream service |
| Not returning the ID to the client | users can't tell you which request failed |
| ECS logs with `traceId` instead of `trace.id` | log platform can't link logs and traces |

## Interview questions

**Q: What is a correlation ID and how do you implement it in Spring Boot?**
A unique ID per request written into every log line. Today: Micrometer Tracing with the OpenTelemetry
bridge; the W3C trace ID goes into the MDC automatically, and the log pattern prints it. Return it to the
client in a header and in error bodies.

**Q: How does the MDC work, and what's the classic bug?**
It's a `ThreadLocal` map read by the log pattern. Pooled threads are reused, so values must be removed in
`finally`; and it doesn't follow work onto other threads without context propagation.

**Q: Request ID vs trace ID?**
A request ID identifies one request in one service. A trace ID identifies an operation across services
(W3C `traceparent`), with a span per step. The trace ID is the modern correlation ID.

**Q: Does sampling at 10 % mean only 10 % of requests have a trace ID?**
No. Every request gets one; sampling decides which traces are exported.

**Q: How do you keep the trace ID in `@Async` methods?**
A `ContextPropagatingTaskDecorator` (Micrometer context propagation) on the executor; Boot applies a
`TaskDecorator` bean to its own executors.

**Q: Should you trust an incoming `traceparent`?**
From your own gateway and services, yes (that's how traces join up). From the internet, the edge should
strip or replace it: otherwise clients choose your trace IDs and can force sampling.
