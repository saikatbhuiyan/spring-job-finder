# ADR 0006: Global exception handling with Problem Details

- **Status:** Accepted; extended by [ADR 0008](0008-exception-handling-hardening.md) and [ADR 0009](0009-request-validation.md)
- **Date:** 2026-11-22
- **Implements and extends:** [ADR 0003](0003-problem-details-for-errors.md)

## Context

ADR 0003 decided that every API error uses RFC 9457 Problem Details and planned a global
`@RestControllerAdvice`. Until now Problem Details came only from Spring Boot's built-in
`ProblemDetailsExceptionHandler` (`spring.mvc.problemdetails.enabled=true`), which only covers Spring
MVC's own exceptions. Verification (`learning/30-dto-pattern.md` §11, `learning/31-global-exception-handling.md`)
showed three gaps:

1. **An unhandled exception in a controller** returned Boot's legacy
   `{"timestamp","status","error","path"}` body as `application/json`, contradicting ADR 0003.
2. **Errors outside Spring MVC** (an exception in a servlet filter, `response.sendError(401)` as Spring
   Security does) never reach any `@RestControllerAdvice`. The container forwards them to `/error`, where
   Boot's `BasicErrorController` answers with the same legacy body.
3. **Validation and binding errors** said only "Invalid request content." / "Failed to read request",
   with no hint *which* field was wrong.

## Decision

1. **`shared/api/GlobalExceptionHandler`**: a `@RestControllerAdvice` extending
   `ResponseEntityExceptionHandler`.
   - Spring MVC's own exceptions (404, 405, 415, invalid API version…) keep the inherited standard mapping.
     Our bean makes Boot's `ProblemDetailsExceptionHandler` back off (`@ConditionalOnMissingBean`), so
     `spring.mvc.problemdetails.enabled` is removed as dead configuration.
   - **Validation** (`MethodArgumentNotValidException`, `HandlerMethodValidationException`) and
     **unreadable bodies** (unknown property, wrong type) become a `validation-error` problem with an
     `errors` array in the shape RFC 9457 uses in its own example: a JSON Pointer for body fields
     (`{"pointer":"#/company/name","detail":"must not be blank"}`), or the name the client used for a
     parameter (`{"parameter":"page_size",…}`). Rejected values are **not** echoed back.
   - Broken or missing JSON becomes `malformed-request-body`. Jackson's message, which names internal
     classes, is never passed on.
   - **Anything else** is logged at ERROR with its stack trace (exactly once) and answered with a generic
     `500` (`"An unexpected error occurred."`). The exception message is never sent.
   - Every problem gets `instance` = the request path (without the query string).
2. **`shared/api/ProblemDetailsErrorController`**: replaces `BasicErrorController` at
   `${spring.web.error.path:/error}`, so errors from filters and the container are Problem Details too.
   It doesn't log the exception again, because Tomcat already logged it before forwarding.
3. **Business failures** are exceptions in the shared kernel (`shared` module API), free of HTTP types:
   a **sealed** `DomainException` with `ResourceNotFoundException` → `404` and `ConflictException` → `409`
   (both `non-sealed`, so modules may subclass them). The handler switches over the sealed hierarchy
   **exhaustively**, so a new kind of failure doesn't compile until its HTTP mapping is decided. Their
   messages are client-safe by contract and become the `detail`.
4. **Problem types** this API defines (`validation-error`, `malformed-request-body`,
   `resource-not-found`, `conflict`) have absolute `type` URIs that resolve to their documentation in
   [`docs/problems.md`](../problems.md). Generic HTTP errors keep `about:blank`. `type` URIs are part of
   the contract and never change.
5. **Bean Validation** (`spring-boot-starter-validation`) is added now, ahead of ADR 0005 decision 8,
   because the handler's main 400 case can't be built and tested without it.
6. Locked in by `GlobalExceptionHandlingTests` (14 HTTP-level tests with test-only endpoints and a test-only
   filter, plus log capture). Mutation checks: removing the catch-all, the error controller or the
   `instance` hook each fails tests, and an unmapped `DomainException` subtype fails compilation.

## Consequences

**Positive**
- One error format for every response, including errors outside Spring MVC (ready for Spring Security).
- Clients can show field-level messages. Operators get stack traces in the log, never in responses.
- New failure types can't silently become 500s.

**Negative / accepted trade-offs**
- The `type` URIs point into the GitHub repository. If the API ever gets its own documentation site, the
  old URIs must keep working (redirects), because changing them is a breaking change.
- JSON Pointers are built from Java property paths. A DTO that renames a property with `@JsonProperty`
  would get a pointer with the Java name. DTOs don't rename properties (records use JSON names).
- `GET /error` requested directly is rejected with a 400 by API version resolution (`/error` has no version
  segment). Forwarded errors are unaffected (tested), and no client has a reason to call `/error`.
- Validation messages are Hibernate Validator's English defaults. Localisation (`Accept-Language`,
  `messages.properties`) is not needed yet.

**Follow-ups**
- When Spring Security is added: an `AccessDeniedException` thrown inside a controller (method security)
  would hit the catch-all and become a 500. Map it (403) or let Security handle it.
- When persistence is added: map `OptimisticLockingFailureException` → `409` and
  `DataIntegrityViolationException` (unique constraint) → `409`, without exposing SQL.
- Add a correlation/trace id to every problem once the request-id filter exists (roadmap).

## References

- RFC 9457 *Problem Details for HTTP APIs* (§3 example with `errors`/`pointer`), RFC 6901 *JSON Pointer*
- Spring Framework reference: "Error Responses", `ResponseEntityExceptionHandler`
- Learning note: `learning/31-global-exception-handling.md`
