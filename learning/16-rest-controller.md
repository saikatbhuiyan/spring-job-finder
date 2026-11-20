# 16 — `@RestController` (= `@Controller` + `@ResponseBody`)

## Goal

Understand how Spring MVC turns a Java method into an **HTTP endpoint**, why
`@RestController` exists, and how to write REST endpoints the production way (status codes,
`ResponseEntity`, DTOs, error format). Everything below was verified by running a scratch
Spring Boot 4.2.0 app (Spring Framework 7.1.0, Jackson 3.1.8) and calling it over real HTTP.

Status: 🧪 verified in a demo. The project has no business endpoints yet, but its **error
format** (Problem Details, §7) is ✅ applied and tested.

---

## 1. The problem `@ResponseBody` solves

Spring MVC was designed for **server-rendered web pages**. In a `@Controller`, a returned
`String` is treated as a **view name** (the name of an HTML template to render), not as data.

```java
@Controller
public class PlainController {

    @GetMapping("/plain/hello")
    public String hello() { return "hello"; }          // means: render the template "hello"

    @GetMapping("/plain/body")
    @ResponseBody                                       // means: "hello" IS the response body
    public String body() { return "hello from @ResponseBody"; }
}
```

Verified:

```text
GET /plain/hello
   -> 500  Circular view path [hello]: would dispatch back to the current handler URL
           [/plain/hello] again. Check your ViewResolver setup!
GET /plain/body
   -> 200 | text/plain;charset=UTF-8
   hello from @ResponseBody
```

Without `@ResponseBody`, Spring looked for a template called `hello`, found none (we have no
template engine), and failed. With it, the return value **is written directly to the HTTP
response body**.

## 2. `@ResponseBody` on the class applies to every method

```java
@Controller
@ResponseBody
public class ClassLevelBody {
    @GetMapping("/classbody/job")
    public JobResponse job() { return new JobResponse(1, "Java Developer", "Acme"); }
}
```

```text
GET /classbody/job  -> 200 | application/json
{"id":1,"title":"Java Developer","company":"Acme"}
```

## 3. `@RestController` is exactly that combination

Verified by reflection:

```text
RestController meta-annotations: [@Controller, @ResponseBody]
```

```java
@RestController            // = @Controller + @ResponseBody on every method
public class JobController { ... }
```

For a **REST API** that returns data (JSON) rather than HTML pages, every method needs
`@ResponseBody`, so `@RestController` saves repeating it. It's also a stereotype, scanned like any
`@Component` (note 07), so it's a singleton bean and must be stateless (note 13 §10).

| | `@Controller` | `@RestController` |
|-|---------------|-------------------|
| Return value means | A **view name** → render HTML | The **response body** → JSON/text |
| Typical app | Server-rendered pages (Thymeleaf) | **REST APIs**, single-page app backends, mobile backends |
| Job Finder uses | — | ✅ |

---

## 4. How the return value becomes JSON: message converters

Spring picks an **`HttpMessageConverter`** based on the **return type** and the client's
**`Accept` header**:

| Return type | Converter | Content-Type (verified) |
|-------------|-----------|-------------------------|
| `String` | String converter | `text/plain;charset=UTF-8` |
| Object / record / `List` | **Jackson** (JSON) | `application/json` |
| `void` with `@ResponseStatus` | — (no body) | none |

Spring Boot 4 uses **Jackson 3** (`tools.jackson`, version 3.1.8 here). Older tutorials show
Jackson 2 (`com.fasterxml.jackson`). The idea is the same, but the package names changed.

**Content negotiation, verified:** asking for XML when only JSON is available:

```text
GET /api/v1/jobs/1  (Accept: application/xml)
   -> 406 Not Acceptable     ("No acceptable representation")
```

The server can't produce what the client asked for, so it says so honestly instead of sending
JSON anyway.

---

## 5. A complete REST controller (verified)

```java
public record JobResponse(long id, String title, String company) {}   // response DTO
public record CreateJobRequest(String title, String company) {}       // request DTO

@RestController
@RequestMapping("/api/v1/jobs")                 // shared, versioned base path
public class JobController {

    @GetMapping                                 // GET /api/v1/jobs
    public List<JobResponse> all() { ... }

    @GetMapping("/{id}")                        // GET /api/v1/jobs/1
    public ResponseEntity<JobResponse> one(@PathVariable long id) {
        var job = jobs.get(id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @PostMapping                                // POST /api/v1/jobs  (JSON body)
    public ResponseEntity<JobResponse> create(@RequestBody CreateJobRequest req) {
        var job = /* save */;
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(job.id()).toUri();
        return ResponseEntity.created(location).body(job);          // 201 + Location
    }

    @DeleteMapping("/{id}")                     // DELETE /api/v1/jobs/2
    @ResponseStatus(HttpStatus.NO_CONTENT)      // 204, no body
    public void delete(@PathVariable long id) { ... }
}
```

(The demo stored jobs in a `Map` inside the controller to stay short. In the real app the
controller calls a `JobService`, as in note 05.)

Verified:

```text
GET    /api/v1/jobs      -> 200 | application/json  [{"id":1,"title":"Java Developer","company":"Acme"}]
GET    /api/v1/jobs/1    -> 200 | application/json  {"id":1,"title":"Java Developer","company":"Acme"}
GET    /api/v1/jobs/99   -> 404 | (no body)
POST   /api/v1/jobs      -> 201 | application/json | Location: http://localhost:PORT/api/v1/jobs/2
                            {"id":2,"title":"Kotlin Engineer","company":"Globex"}
DELETE /api/v1/jobs/2    -> 204 | (no body)
```

### The annotations

> Each binding annotation in depth, with verified edge cases: [note 17](17-request-mapping-and-binding.md).

| Annotation | Meaning |
|------------|---------|
| `@RequestMapping("/api/v1/jobs")` on the class | Base path for every method |
| `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` | HTTP method + path |
| `@PathVariable` | A value from the URL path (`/jobs/{id}`) |
| `@RequestParam` | A query parameter (`/jobs?keyword=java`) |
| `@RequestBody` | Read the JSON body into an object (Jackson again, in reverse) |
| `@ResponseStatus` | A fixed status code for the method |
| `ResponseEntity<T>` | Full control: status + headers + body |

(`@PathVariable long id` works without naming it, `@PathVariable("id")`, because Spring Boot's
parent POM compiles with `-parameters`; see note 15 §4.)

### Status codes a production API should use

| Situation | Status | How |
|-----------|--------|-----|
| Read OK | `200 OK` | Return the object, or `ResponseEntity.ok(...)` |
| Created | `201 Created` + `Location` header | `ResponseEntity.created(uri).body(...)` |
| Deleted / nothing to return | `204 No Content` | `@ResponseStatus(NO_CONTENT)` on a `void` method |
| Not found | `404 Not Found` | `ResponseEntity.notFound()`, or better, an exception mapped to 404 (🔜) |
| Invalid input | `400 Bad Request` | Bean Validation (`@Valid`) (🔜) |
| Server bug | `500` | Never on purpose; the global handler catches it |

Returning `200` for everything (with `"success": false` inside) is a common beginner mistake.
Clients, proxies and monitoring all rely on real status codes.

---

## 6. Errors: what the client sees (verified)

Two failures with Boot's **default** error handling:

```text
POST /api/v1/jobs  with body "{not json"
   -> 400 | application/json
   {"timestamp":"2026-11-20T13:21:03.230Z","status":400,"error":"Bad Request","path":"/api/v1/jobs"}

GET /api/v1/jobs/boom    (controller throws IllegalStateException("something broke"))
   -> 500 | application/json
   {"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/v1/jobs/boom"}
```

✅ **Good security default:** the exception message `something broke` and the stack trace were
**not** sent to the client (they're only in the server log). Leaking internals helps attackers.

But this JSON shape is **Spring-specific**. There's a standard for it.

---

## 7. ✅ Applied: RFC 9457 Problem Details

**RFC 9457 "Problem Details for HTTP APIs"** is the internet standard for error responses.
Content type `application/problem+json`, with standard fields `type`, `title`, `status`,
`detail`, `instance`, plus any custom fields. Clients and API tools understand it.

Enabled in [application.properties](../src/main/resources/application.properties):

```properties
spring.mvc.problemdetails.enabled=true
```

The same failures, verified:

```text
POST /api/v1/jobs  with "{not json"
   -> 400 | application/problem+json
   {"detail":"Failed to read request","status":400,"title":"Bad Request"}

GET /api/v1/nothing-here
   -> 404 | application/problem+json
   {"detail":"No static resource api/v1/nothing-here.","status":404,"title":"Not Found"}
```

**Why now, before any endpoint exists:** the error format is part of the **API contract**. Changing
it after clients depend on it is a breaking change. Setting it on day one is free.

It's locked in by a test,
[ErrorResponseTests.java](../src/test/java/com/shahabuddin/jobfinder/ErrorResponseTests.java):

```java
client.get().uri("/api/v1/does-not-exist")
      .exchange()
      .expectStatus().isNotFound()
      .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
      .expectBody()
      .jsonPath("$.status").isEqualTo(404)
      .jsonPath("$.title").isEqualTo("Not Found");
```

```text
Tests run: 5 (HealthEndpointTests) + 1 (ErrorResponseTests), Failures: 0
```

Next (🔜): a global `@RestControllerAdvice` that maps **our own** exceptions, e.g.
`JobNotFoundException`, to Problem Details with useful `detail` text, plus `@Valid` request
validation that returns field-level errors.

---

## 8. Production checklist for REST controllers

1. **`@RestController`** for APIs; `@Controller` only for server-rendered pages.
2. **Versioned base path:** `/api/v1/...`, so `v2` can be introduced without breaking clients
   (✅ configured, see [note 18](18-api-versioning.md)).
3. **Resource-oriented URLs:** plural nouns (`/jobs`, `/jobs/{id}`), with the HTTP method as the
   verb. Not `/getJobs` or `/createJob`.
4. **DTOs (records) at the boundary:** never return database entities (note 05 §2).
5. **Correct status codes:** `201` + `Location` on create, `204` on delete, `404` / `400` for client
   errors.
6. **Thin controllers:** parse the request → call the service → map to a DTO. No business logic.
7. **Standard error format:** Problem Details ✅. Never leak stack traces or exception messages.
8. **Stateless:** controllers are singletons (note 13 §10).
9. **Validate input** (`@Valid`), **document the API** (OpenAPI), **test** every endpoint (🔜).

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| `@Controller` for a JSON API, without `@ResponseBody` | `Circular view path` / 500 (verified) |
| Returning entities directly | Leaks fields; ties the API to the database schema |
| `200 OK` for every outcome | Clients and monitoring can't tell success from failure |
| `POST` create returning `200` without `Location` | Not RESTful; the client doesn't know the new resource's URL |
| Verbs in URLs (`/api/getJobs`) | Not RESTful; use `GET /api/v1/jobs` |
| Mutable fields in a controller | Data leaks between users (note 13 §10) |
| Leaking `e.getMessage()` / stack traces to clients | Security risk; Boot hides them by default (verified) |

## Interview questions

**Q: Difference between `@Controller` and `@RestController`?**
`@RestController` = `@Controller` + `@ResponseBody`: return values are written to the response
body (e.g. as JSON) instead of being resolved as view names.

**Q: What does `@ResponseBody` do?**
Tells Spring MVC to serialise the return value into the HTTP response body using an
`HttpMessageConverter`.

**Q: How does Spring turn an object into JSON?**
`MappingJackson…`/Jackson `HttpMessageConverter`, chosen by return type and the `Accept` header
(content negotiation). Spring Boot 4 uses Jackson 3.

**Q: What happens if the client sends `Accept: application/xml` and only JSON is supported?**
`406 Not Acceptable`.

**Q: When do you use `ResponseEntity`?**
When you need to control the status code and headers, e.g. `201 Created` with a `Location` header.

**Q: What is RFC 9457 / Problem Details?**
A standard JSON error format (`application/problem+json`) with `type`, `title`, `status`, `detail`,
and `instance`. Enable it in Boot with `spring.mvc.problemdetails.enabled=true`.

**Q: Why version an API (`/api/v1`)?**
So incompatible changes can ship as `v2` without breaking existing clients.
