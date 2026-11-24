# ADR 0013: Request correlation (trace IDs)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Extends:** [ADR 0003](0003-problem-details-for-errors.md) (`traceId` in every problem),
  [ADR 0004](0004-logging-per-environment.md) (trace ID in dev and prod log lines),
  [ADR 0007](0007-cors-policy.md) (`X-Trace-Id` exposed),
  [ADR 0008](0008-exception-handling-hardening.md) (filter exceptions logged inside the trace)

## Context

A client reports "I got a 500 at 14:02". Without an ID that ties together everything one request
logged (and, later, everything it caused in other services), finding those lines means guessing by
timestamp and path among all concurrent requests. Measured before deciding (`learning/38-request-correlation-id.md`):

**A hand-rolled `X-Request-Id` filter (MDC)**, the usual tutorial version:
- Without `MDC.remove` in `finally`, an anonymous request on a reused Tomcat thread **inherited the previous
  client's ID** (in its log lines and its response header).
- Client-supplied IDs were accepted unchecked: 7 000 characters, or text imitating a log line
  (`x] INFO c.s.j.AuthService : admin login OK [`). (Tomcat itself rejected control characters.)
- The MDC was **empty on other threads**: a task on a plain executor and on Boot's
  `applicationTaskExecutor` logged without the ID.
- Outgoing calls carried nothing; every service would need the same filter and the same header name.

**Spring Boot 4.2's tracing (Micrometer Tracing + OpenTelemetry bridge)**, scratch app:
- Every request got a W3C trace ID in the MDC (`traceId`, `spanId`), **including the ~90 % that the default
  sampling probability (0.1) does not sample**. Sampling decides what is exported, not what is logged.
- A caller's `traceparent` was continued; malformed ones (non-hex, all-zero ID, trailing text) were ignored
  and a new trace started. An instrumented `RestClient` sent `traceparent` downstream.
- No response header carried the ID: the client had nothing to report.
- Boot's defaults also **accepted B3 headers** (`b3`, `X-B3-*`) from any client and **forwarded a client's
  `baggage` header** (`user.role=admin,secret=hunter2`) verbatim on outgoing calls.
- `spring-boot-starter-opentelemetry` adds the OTLP exporters; its metrics registry **pushed to
  `http://localhost:4318` every minute with no configuration at all**. (An OpenTelemetry Collector
  happened to be running locally, from another project, and received the scratch app's metrics.)
- `@Async`-style work lost the trace unless a `ContextPropagatingTaskDecorator` bean exists.
- An exception thrown by a **servlet filter** escaped the observation filter, which closed the trace; the
  container then logged the stack trace **with an empty MDC**: the one line a client's ID should find had
  none. A response header set before the failure survived both `sendError` and the error dispatch.
- Prod ECS JSON wrote the MDC keys as `traceId` / `spanId`; ECS tools join logs and traces on `trace.id` /
  `span.id`.

## Decision

1. **The correlation ID is the W3C trace ID.** Boot's Micrometer Tracing auto-configuration with the
   OpenTelemetry bridge (`spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`),
   **not** a hand-rolled request-ID filter and **not** `spring-boot-starter-opentelemetry` (no exporter until
   there is a collector to send to). One standard ID for logs today, and for distributed traces, other
   services and outgoing calls without further code.
2. **Only W3C Trace Context** in and out (`management.tracing.propagation.type=w3c`); **no baggage**
   (`management.tracing.baggage.enabled=false`).
3. **`X-Trace-Id` response header** on every response (also errors, 429s and actuator), set by
   `TraceIdFilter` right after the observation filter (`HIGHEST_PRECEDENCE + 2`). CORS-exposed.
4. **`traceId` in every Problem Details body**, from all three writers (`GlobalExceptionHandler`,
   `ProblemDetailsErrorController`, `RateLimitFilter`), so the ID is in what users copy into bug reports.
5. **Exceptions escaping later filters are logged by `TraceIdFilter`**, once, inside the trace, with the
   same 5xx policy as `GlobalExceptionHandler` (generic body via the error controller; recorded for
   metrics with `ServerHttpObservationFilter.recordHandledException`). I/O exceptions (mostly disconnected
   clients) keep the container's handling.
6. **Async work stays in the trace**: a `ContextPropagatingTaskDecorator` bean, which Boot applies to
   `applicationTaskExecutor` (`@Async`, Spring Modulith's `@ApplicationModuleListener`).
7. **Logs**: dev pattern shows the trace ID; prod ECS renames the MDC keys to `trace.id` / `span.id`.
8. **Trust**: a caller's valid `traceparent` is continued (a gateway or another service). The edge proxy
   **must strip or replace `traceparent` from the public internet**, as it does `X-Forwarded-For`
   (ADR 0012): the caller otherwise chooses our trace ID and, once spans are exported, the sampling decision
   (`-01` forced sampling in the scratch app).
9. Locked in by `TracingTests` (11), `GlobalExceptionHandlingTests` (filter exception logged with the trace
   ID; a wrapped one recorded under its cause), `RateLimitTests`, `CorsTests`, and `ProfileConfigurationTests`
   (dev and prod log lines of a request, in a child JVM). Mutation checks:
   accepting B3, enabling baggage, removing the task decorator, letting the container log filter
   exceptions, ordering the filter after the rate limit, dropping `traceId` from either error writer, not
   recording the exception (or recording the `ServletException` wrapper), not renaming the ECS keys, the dev pattern without the ID, and not exposing the
   header each fail the test that guards it.

## Consequences

**Positive**
- One ID from the client's bug report to every log line of the request, in every environment.
- Standard: the same ID will appear in a tracing backend and in other services' logs, with no custom code.
- Spring Modulith's async listeners will log with the ID of the request that caused them.

**Negative / accepted trade-offs**
- OpenTelemetry SDK on the classpath (~1.4 MB) and a span per request, recorded but not exported.
- Two more filter responsibilities in `TraceIdFilter` (header and filter-exception logging), placed
  together because both must run inside the trace.
- Exceptions in the few filters that run before it (CORS, character encoding, the observation filter
  itself) are still logged by the container without a trace ID.
- Log lines written during the error dispatch (DEBUG only) have no trace ID.
- An executor created by hand (not Boot's) needs the decorator set explicitly.

**Follow-ups**
- With a collector: `spring-boot-starter-opentelemetry` with explicit OTLP endpoints, and a sampling policy
  that public callers can't force (parent-based only for trusted callers, or tail sampling in the
  collector).
- `traceresponse` (W3C Trace Context Level 2) instead of `X-Trace-Id` once it is a Recommendation.

## References

- W3C Trace Context (`traceparent`, `tracestate`); W3C Baggage
- Spring Boot reference: Tracing; Micrometer Tracing; Elastic Common Schema `trace.id`, `span.id`
- Learning note: `learning/38-request-correlation-id.md`
