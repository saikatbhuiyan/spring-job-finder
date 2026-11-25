# ADR 0008: Exception handling hardening (5xx policy, one advice, fixed locale)

- **Status:** Accepted; extended by [ADR 0009](0009-request-validation.md), [ADR 0013](0013-request-correlation.md) (filter exceptions logged inside the trace) and [ADR 0015](0015-testing-strategy.md) (unparseable query strings are a `400`, not a `500`)
- **Date:** 2026-11-23
- **Extends:** [ADR 0006](0006-global-exception-handling.md)

## Context

ADR 0006 put all exception handling in `GlobalExceptionHandler` and `ProblemDetailsErrorController`.
Probing cases it didn't cover, over real HTTP (`learning/33-exception-handling-in-production.md`), found:

1. **Silent 500s from inherited handling.** `ResponseEntityExceptionHandler` answers some exceptions with a
   `500` and **logs nothing**: a response Jackson can't serialize (`HttpMessageNotWritableException`), a
   misnamed `@PathVariable` (`MissingPathVariableException`, both verified). The latter also put an
   internal name in the `detail` (`"Required path variable 'jobId' is not present."`). ADR 0006 only covered
   *our* catch-all.
2. **Failures after the response was committed** (serialization failing halfway through a large body)
   produced one WARN line without a stack trace (`Response already committed. Ignoring`), and metrics
   recorded a clean `200 SUCCESS`: a truncated response was invisible.
3. **A second `@ControllerAdvice` is silently shadowed.** Spring asks advice beans in order and uses the
   first one with **any** matching handler. A module's own advice for its own exception never ran; the
   global catch-all turned it into a `500`.
4. **Mixed-language errors.** The request locale followed `Accept-Language`, so Bean Validation messages
   were German or French (`"darf nicht leer sein"`) inside an otherwise English problem. ADR 0006's
   statement that validation messages are "Hibernate Validator's English defaults" was wrong for any
   client sending `Accept-Language`, and for a server whose OS locale isn't English.
5. **Client disconnects** while JSON was being written logged a WARN per disconnect, while the same
   disconnect on other write paths was a DEBUG line.

Verified as **already correct** (no change, now covered by tests): handled 5xx exceptions are recorded in
`http.server.requests` and on the observation (Spring Framework 7 does this after the `@ExceptionHandler`
ran, for 5xx only, so expected 4xx outcomes aren't counted as errors). `@Async void` and `@Scheduled`
failures are logged by Spring and a scheduled task keeps running.

## Decision

1. **One 5xx policy for every handler**, enforced in `GlobalExceptionHandler.handleExceptionInternal`, the
   single exit of every handler (ours and inherited). Any 5xx is logged **once** at ERROR with its stack
   trace (`GET /api/v1/... failed with 500`) and answered with the generic body
   (`"An unexpected error occurred."`), whatever the inherited handler would have said.
2. **After commit**, the failure is logged at ERROR ("failed after the response was committed") and
   recorded on the request's observation (`ServerHttpObservationFilter.recordHandledException`), so
   metrics show `status=200, exception=HttpMessageNotWritableException` instead of a clean success.
3. **Client disconnects** are not errors. Only a write failure caused by Spring's
   `AsyncRequestNotUsableException` (raised by its response wrapper when the client connection is gone)
   is passed to Spring's own disconnected-client handling (DEBUG). Spring's broader
   `DisconnectedClientHelper` is **not** used for classification: it treats any `EOFException` in the cause
   chain as a disconnect (tested), which could hide a real failure as an empty `200`.
4. **Exactly one `@ControllerAdvice`**, in `shared.api`. Enforced by an ArchUnit rule
   (`CONTROLLER_ADVICE_ONLY_IN_SHARED_API`) and by a context test (catches a library's advice too).
   Modules report business failures as `DomainException` subtypes (ADR 0006). If a module ever needs its
   own advice, it must be `@Order`ed before the global one and this ADR revisited.
5. **Fixed English locale for requests**: `spring.web.locale=en`, `spring.web.locale-resolver=fixed`.
   Error text is for developers and logs; clients localize from the problem `type` and `pointer`. (Boot
   refuses to start with `fixed` and no locale.)
6. Locked in by `GlobalExceptionHandlingTests` (now 20 tests) and `ArchitectureRulesTests`. Mutation checks:
   removing the 5xx policy, the disconnect case, the observation record or the locale settings, or adding a
   second advice, each fails the test that guards it.

## Consequences

**Positive**
- No 5xx can go unlogged or reveal internals, including ones Spring produces.
- Logs and metrics stay meaningful: real failures are ERRORs, routine disconnects aren't.
- One place to look for exception mapping; a shadowed handler can't be introduced unnoticed.
- One language per response, independent of client headers and server OS.

**Negative / accepted trade-offs**
- A deliberately thrown `ResponseStatusException(SERVICE_UNAVAILABLE, "...")` loses its message too: every
  5xx detail is generic. Client-relevant failures should be 4xx domain exceptions.
- Error text can't be localized by the API. If a frontend needs translated messages, the fix is a stable
  machine-readable code per violation (follow-up), not `Accept-Language`.
- A failure after commit still reaches the client as an incomplete `200`; HTTP offers no way to change the
  status once sent. Paginated responses keep bodies small, which makes this rare.

**Follow-ups**
- Add a machine-readable `code` (e.g. the constraint name `NotBlank`) to each validation violation when a
  frontend needs localized messages.
- When `@Async` methods are introduced: register an `AsyncUncaughtExceptionHandler`/`AsyncConfigurer` only
  if the default ERROR log isn't enough, and never fire-and-forget a `CompletableFuture` (its failure is
  logged by nobody).

## References

- Spring Framework reference: "Exceptions", `ResponseEntityExceptionHandler`, "Observability"
- Learning note: `learning/33-exception-handling-in-production.md`
