# 31 — Global Exception Handling with `@RestControllerAdvice`

## Goal

Without global handling, every controller would need `try/catch` blocks, and errors would come back
in different shapes. Spring lets you handle exceptions **in one place** with `@RestControllerAdvice` +
`@ExceptionHandler`. This note explains how Spring resolves exceptions, why a `@RestControllerAdvice`
**alone isn't enough**, and walks through Job Finder's implementation: Problem Details for every error,
field-level validation messages, sealed domain exceptions, safe 500s. Every behavior was verified by
tests over real HTTP.

Status: ✅ **applied** ([ADR 0006](../docs/adr/0006-global-exception-handling.md), catalogue in
[`docs/problems.md`](../docs/problems.md)). Boot 4.2.0, Spring Framework 7.1.0, Jackson 3.1.8,
Hibernate Validator 9.1.

---

## 1. How Spring MVC handles an exception

```text
 Request ──▶ Filters ──▶ DispatcherServlet ──▶ Controller method ──✗ throws
                │                 │
                │                 ▼  HandlerExceptionResolvers, in order:
                │        1. ExceptionHandlerExceptionResolver  → @ExceptionHandler methods
                │           (in the controller itself, then in @ControllerAdvice classes)
                │        2. ResponseStatusExceptionResolver    → @ResponseStatus / ResponseStatusException
                │        3. DefaultHandlerExceptionResolver    → Spring MVC's own exceptions (405, 415…)
                │                 │ nobody handled it
                ▼                 ▼
          ✗ exception or sendError(…) ──▶ servlet container (Tomcat) ──▶ forwards to /error
                                                                        └▶ ErrorController
                                                                           (Boot: BasicErrorController)
```

Two consequences:

1. **`@ExceptionHandler` only sees exceptions thrown inside the `DispatcherServlet`**: controllers,
   argument binding, validation, message conversion.
2. **Errors in filters** (Spring Security!) and the container never reach it. They end up at `/error`.

---

## 2. The building blocks

```java
@RestControllerAdvice                       // = @ControllerAdvice + @ResponseBody: applies to all controllers
class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)    // handles this type and its subclasses
    ProblemDetail handle(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
```

| Concept | Meaning |
|---------|---------|
| `@ExceptionHandler(X.class)` | A method that handles `X` (and subclasses). Its parameters can include the exception, `HttpServletRequest`, `WebRequest`… |
| In a controller | Handles exceptions of **that controller only** |
| `@ControllerAdvice` | A class whose `@ExceptionHandler`s apply to **all** controllers (narrow it with `basePackages`, `assignableTypes`, `annotations`) |
| `@RestControllerAdvice` | `@ControllerAdvice` + `@ResponseBody`: return values are written as JSON |
| Matching | The **closest** exception type wins (`ResourceNotFoundException` before `Exception`) |
| Several advices | Ordered by `@Order`; the first one with a matching handler wins |
| `ProblemDetail` | Spring's RFC 9457 type. Returning it sets `application/problem+json` and the status |
| `ResponseEntityExceptionHandler` | A base class that already maps ~20 Spring MVC exceptions to `ProblemDetail`; override its `handle…` methods to customize |

### Spring Boot's built-in handler

`spring.mvc.problemdetails.enabled=true` registers Boot's `ProblemDetailsExceptionHandler`, an empty
subclass of `ResponseEntityExceptionHandler`. It is `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`
(verified in the Boot 4.2.0 bytecode), so **as soon as you write your own subclass, Boot's backs off**.
Job Finder's test `exactlyOneExceptionHandlerAndErrorControllerAreActive` asserts there is exactly one.
The property is therefore removed as dead configuration.

---

## 3. What was broken before (verified)

With only Boot's handler:

| Situation | Response |
|-----------|----------|
| Controller throws `IllegalStateException` | `500 application/json {"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/v1/boom"}`: Boot's **legacy** body, not Problem Details |
| Filter throws | Same legacy body |
| Filter calls `response.sendError(401)` (what Spring Security does) | `401 application/json {"timestamp":…,"error":"Unauthorized",…}` |
| `@Valid` body with 3 errors | `400 {"detail":"Invalid request content."}`, and the 3 field errors appear **only in the log** |
| Unknown property / wrong type | `400 {"detail":"Failed to read request"}` |

So a `@RestControllerAdvice` is needed (rows 1, 4, 5), **plus** something at `/error` (rows 2, 3).

---

## 4. Job Finder's design

```text
shared/                                   ← shared kernel module (Spring Modulith)
├── DomainException            (sealed)   ← thrown by use cases; no HTTP types
│   ├── ResourceNotFoundException → 404
│   └── ConflictException        → 409
└── api/
    ├── GlobalExceptionHandler            ← @RestControllerAdvice extends ResponseEntityExceptionHandler
    ├── ProblemDetailsErrorController     ← replaces Boot's BasicErrorController at /error
    ├── ProblemTypes                      ← type URIs → docs/problems.md
    └── Violation                         ← one entry of "errors"
```

| Error | Handled by | Response |
|-------|------------|----------|
| Spring MVC's own (404 path, 405, 415, invalid API version…) | inherited `ResponseEntityExceptionHandler` | standard Problem Details (`about:blank`) |
| `@Valid` body, constraint on a parameter | overridden `handleMethodArgumentNotValid` / `handleHandlerMethodValidationException` | `400 validation-error` + `errors[]` |
| Unknown property, wrong JSON type | overridden `handleHttpMessageNotReadable` | `400 validation-error` + `errors[]` |
| Broken / missing JSON | same | `400 malformed-request-body` |
| `ResourceNotFoundException` / `ConflictException` | `@ExceptionHandler(DomainException.class)` | `404` / `409` with the exception's message |
| Anything else | `@ExceptionHandler(Exception.class)` | `500` generic, stack trace logged |
| Filter exception, `sendError`, container | `ProblemDetailsErrorController` | Problem Details for that status |

---

## 5. Domain exceptions: sealed and exhaustive (verified)

```java
public abstract sealed class DomainException extends RuntimeException
        permits ResourceNotFoundException, ConflictException { … }

public non-sealed class ResourceNotFoundException extends DomainException {
    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " '" + id + "' was not found.");
    }
}
```

```java
@ExceptionHandler(DomainException.class)
ResponseEntity<Object> handleDomainException(DomainException ex, WebRequest request) {
    ProblemDetail problem = switch (ex) {        // no default branch!
        case ResourceNotFoundException e -> problem(NOT_FOUND, RESOURCE_NOT_FOUND, "Resource not found", e.getMessage());
        case ConflictException e         -> problem(CONFLICT, CONFLICT_TYPE, "Conflict", e.getMessage());
    };
    …
}
```

- **Sealed** (Java 17): only the listed classes may extend `DomainException`, so the compiler knows all
  cases, and a `switch` without `default` is **exhaustive**.
- **Verified:** adding a third permitted subtype without mapping it broke the build:

  ```text
  [ERROR] GlobalExceptionHandler.java:[75,33] the switch expression does not cover all possible input values
  ```

  A new kind of failure **can't** silently become a 500.
- **`non-sealed`** subtypes let a module add specifics (`JobNotFoundException extends ResourceNotFoundException`).
  They're still handled by the `ResourceNotFoundException` case.
- **No HTTP in the exceptions.** No `@ResponseStatus`, no `HttpStatus`: the domain stays web-agnostic
  (ArchUnit rule, note 19). The web layer decides the status.
- They live in the `shared` module's **top-level package**, which is its public API in Spring Modulith, so
  every module may use them.

```text
GET /api/v1/error-probe/jobs/42 → 404 application/problem+json
{"type":"https://github.com/…/docs/problems.md#resource-not-found","title":"Resource not found",
 "status":404,"detail":"Job '42' was not found.","instance":"/api/v1/error-probe/jobs/42"}
```

---

## 6. Validation errors that say *which* field (verified)

```text
POST /api/v1/error-probe/jobs
{"title": " ", "salary": -5, "company": {"name": ""}, "tags": ["java", ""]}

400 application/problem+json
{
  "type": "https://github.com/…/docs/problems.md#validation-error",
  "title": "Invalid request",
  "status": 400,
  "detail": "The request contains invalid fields or parameters.",
  "instance": "/api/v1/error-probe/jobs",
  "errors": [
    {"pointer": "#/company/name", "detail": "must not be blank"},
    {"pointer": "#/salary",       "detail": "must be greater than 0"},
    {"pointer": "#/tags/1",       "detail": "must not be blank"},
    {"pointer": "#/title",        "detail": "must not be blank"}
  ]
}
```

- **Every** violation is reported in one response, so the client can fix all of them at once.
- **`pointer`** is a JSON Pointer (RFC 6901) into the request body. That's the format RFC 9457 itself uses in
  its example. Bean paths are converted: `company.name` → `#/company/name`, `tags[1]` → `#/tags/1`.
- **Sorted** (validation order isn't guaranteed), so the same input always gives the same response.
- **The rejected value (`-5`) isn't echoed back.** It could be a password or personal data, and it would end
  up in client logs. The test asserts the body doesn't contain it.

### Parameters: report the name the *client* used

```java
@GetMapping("/jobs")
List<String> search(@RequestParam(name = "page_size", defaultValue = "20") @Max(100) int pageSize)
```

```text
GET …/jobs?page_size=500 → 400 {"errors":[{"parameter":"page_size","detail":"must be less than or equal to 100"}], …}
```

A constraint directly on a parameter triggers Spring's **method validation** (6.1+), which raises
`HandlerMethodValidationException`. The handler uses its **Visitor**, which passes the `@RequestParam`
annotation, to report `page_size` rather than the Java name `pageSize`.

### Unreadable bodies

| Body | Jackson 3 exception (in `HttpMessageNotReadableException`) | Response |
|------|-------------------------------------------------------------|----------|
| `{…, "featured": true}` (strict mode, note 30 §8) | `UnrecognizedPropertyException` | `validation-error`, `{"pointer":"#/featured","detail":"is not a known property"}` |
| `{"salary": "lots"}` | `InvalidFormatException` (a `MismatchedInputException`) | `validation-error`, `{"pointer":"#/salary","detail":"has an invalid value or type"}` |
| `{"title": ` (broken) or empty body | parse error / no body | `malformed-request-body`: "The request body is missing or is not valid JSON." |

The pointer comes from Jackson's `JacksonException.getPath()`. **Jackson's own message is never returned**,
because it names internal classes (`Cannot construct instance of demo.Job…`, note 30). The test asserts the
body contains neither "jackson" nor "com.shahabuddin".

---

## 7. The safe 500 (verified)

```java
@ExceptionHandler(Exception.class)
ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest servletRequest, WebRequest request) {
    log.error("Unhandled exception on {} {}", servletRequest.getMethod(), servletRequest.getRequestURI(), ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    return handleExceptionInternal(ex, problem, new HttpHeaders(), INTERNAL_SERVER_ERROR, request);
}
```

The test throws `new IllegalStateException("jdbc password=hunter2")`:

```text
response: 500 {"title":"Internal Server Error","status":500,"detail":"An unexpected error occurred.",
               "instance":"/api/v1/error-probe/boom"}         ← no "hunter2"
log:      ERROR … GlobalExceptionHandler : Unhandled exception on GET /api/v1/error-probe/boom
          java.lang.IllegalStateException: jdbc password=hunter2
              at …ErrorProbeController.boom(…)                ← full stack trace, logged exactly once
```

- **Never** put `ex.getMessage()` of an unexpected exception in a response. It can contain SQL, file paths,
  hostnames or secrets.
- Log **5xx at ERROR** with the stack trace (it's a bug to fix). Domain exceptions and validation errors are
  the *client's* problem and are logged at DEBUG. Logging them at ERROR would flood alerts.

---

## 8. Errors outside Spring MVC: the error controller (verified)

```java
@RestController
class ProblemDetailsErrorController implements ErrorController {     // Boot's BasicErrorController backs off

    @RequestMapping("${spring.web.error.path:${error.path:/error}}")   // all methods: a failed POST arrives as POST
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);                          // from RequestDispatcher.ERROR_STATUS_CODE
        ProblemDetail problem = ProblemDetail.forStatus(status);
        if (status.is5xxServerError()) problem.setDetail("An unexpected error occurred.");
        …instance from RequestDispatcher.ERROR_REQUEST_URI…
    }
}
```

```text
filter throws          → 500 application/problem+json {"title":"Internal Server Error","detail":"An unexpected error occurred.","instance":"/api/v1/filter-probe/throws"}
filter sendError(401)  → 401 application/problem+json {"title":"Unauthorized","status":401,"instance":"/api/v1/filter-probe/unauthorized"}
sendError(401) on /actuator/health → 401 application/problem+json (unversioned paths work too)
```

Two findings while building it:

1. **Duplicate logging.** The first version logged the exception here too, and the stack trace appeared
   **twice**, because Tomcat's `StandardWrapperValve` already logs it ("Servlet.service() … threw exception")
   before forwarding to `/error`. The log call was removed, and the test now asserts the trace appears
   **exactly once**.
2. **`GET /error` directly → 400.** API versioning (version = 2nd path segment, note 18) rejects `/error`
   ("No path segment at index 1") before the controller runs. Forwarded errors are not affected (tested,
   including unversioned `/actuator` paths), and no client has a reason to call `/error`, so this is
   accepted and documented.

---

## 9. `instance` on every problem

Spring did **not** fill `instance` for `ProblemDetail`s returned from `@ExceptionHandler` methods (the first
test run failed: `No value at JSON path "$.instance"`). The fix is in one place: every handler goes through
`handleExceptionInternal`, and the `createResponseEntity` hook sets `instance` to the request path:

```java
@Override
protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem && problem.getInstance() == null
            && request instanceof ServletWebRequest servletWebRequest) {
        problem.setInstance(toInstance(servletWebRequest.getRequest().getRequestURI()));   // path only, no query string
    }
    return super.createResponseEntity(body, headers, status, request);
}
```

This also covers the **inherited** handlers. The 405 test checks `instance` too. The query string is left
out on purpose, since it may carry personal data.

---

## 10. Problem `type` URIs

RFC 9457: `type` is **the** identifier clients should compare on. It should be an absolute URI and, ideally,
point to documentation. Job Finder's custom types resolve to [`docs/problems.md`](../docs/problems.md) in the
repository:

```text
https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#validation-error
```

Generic HTTP errors (404 unknown path, 405, 500) keep `about:blank` (`type` omitted), because the status
already says everything. **Type URIs are API contract: never change them.**

---

## 11. Verification summary

| Check | Result |
|-------|--------|
| `GlobalExceptionHandlingTests` (14 tests over real HTTP: 500, 404/409 domain, body/parameter validation, unknown property, wrong type, malformed and missing body, 405, filter exception, filter `sendError`, `/error`, exactly-one-handler) | ✅ |
| Full suite `mvn clean test` | **49 tests, 0 failures** (was 35) |
| Mutation: remove catch-all `@ExceptionHandler(Exception.class)` | 1 test fails (500 not logged by us) |
| Mutation: remove `ProblemDetailsErrorController` | 2 filter tests fail: `Content-Type application/json is not compatible with application/problem+json` |
| Mutation: remove the `instance` hook | 5 tests fail |
| Mutation: new `DomainException` subtype without mapping | **compile error** (non-exhaustive switch) |
| Packaged jar, `prod` profile, `curl` | 404/405 are `application/problem+json` with `instance` |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Relying on `@RestControllerAdvice` alone | Filter and Security errors bypass it and return a different format (verified, §3) |
| Returning `ex.getMessage()` for unexpected exceptions | Leaks SQL, paths or secrets |
| `try/catch` in every controller | Duplicate, inconsistent error code; let exceptions propagate to the advice |
| Two `ResponseEntityExceptionHandler` beans (yours + Boot's) | Unpredictable handler; extend it once (Boot's backs off) |
| `@ResponseStatus` / `HttpStatus` on domain exceptions | Web concerns in the domain (breaks the ArchUnit rule) |
| Catch-all `Exception` handler plus `@ResponseStatus` exceptions | The catch-all wins: `@ExceptionHandler` runs before `ResponseStatusExceptionResolver` |
| Catch-all + Spring Security method security | `AccessDeniedException` becomes a 500; map it to 403 (follow-up in ADR 0006) |
| Logging 4xx at ERROR | Alert floods for client mistakes |
| Logging the same exception in several places | Duplicate stack traces (verified, §8) |
| Echoing rejected values in validation errors | Personal data in responses and client logs |
| Returning `200` with an error body | Clients, caches and monitoring treat failures as success |

## Interview questions

**Q: `@ControllerAdvice` vs `@RestControllerAdvice`?**
`@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`: handler return values are serialized to the
response body (JSON) instead of being resolved as views.

**Q: How does Spring pick the `@ExceptionHandler` for an exception?**
`ExceptionHandlerExceptionResolver` looks first in the controller, then in `@ControllerAdvice` beans (by
order). Within one class, the handler whose declared type is closest to the thrown type in the class
hierarchy wins.

**Q: Why extend `ResponseEntityExceptionHandler`?**
It already maps Spring MVC's exceptions (405, 415, missing parameters, validation, unreadable body…) to
Problem Details. You override only what you want to refine, and Boot's own handler backs off.

**Q: Which errors does a `@RestControllerAdvice` not catch?**
Errors outside the `DispatcherServlet`: exceptions in servlet filters (e.g. Spring Security), `sendError` calls,
and container errors. They're forwarded to `/error` and handled by an `ErrorController`.

**Q: How do you return validation errors?**
Handle `MethodArgumentNotValidException` (body) and `HandlerMethodValidationException` (parameters), and return
a 400 Problem Details response with an `errors` list of field or parameter, plus message. Don't echo the rejected values.

**Q: What should a 500 response contain?**
A generic message only. The details (stack trace, message) go to the log, ideally with a correlation id that the
client can quote.

**Q: How do you map domain exceptions to HTTP without coupling the domain to HTTP?**
Domain exceptions carry no status. The web layer's handler maps types to status codes. A sealed hierarchy plus an
exhaustive `switch` makes the compiler enforce that every type has a mapping.
