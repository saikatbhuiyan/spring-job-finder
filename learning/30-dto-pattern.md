# 30 — The DTO (Data Transfer Object) Pattern

## Goal

A **DTO** is a simple object whose only job is to **carry data across a boundary**: here, between
the HTTP API and the application. This note shows **why** returning or accepting JPA entities in a REST
API goes wrong (five failures, each reproduced), how to design request and response DTOs with Java
records, where mapping belongs, and what Job Finder now enforces.

Verified in a scratch app (Boot 4.2.0, Jackson 3.1.8, Hibernate 7.4.15, Hibernate Validator 9.1, MapStruct
1.6.3, H2, `open-in-view=false`) with real HTTP calls (`curl`), and in the project with tests.
Status: ✅ **applied** (ArchUnit rule, strict JSON binding, [ADR 0005](../docs/adr/0005-dtos-at-the-api-boundary.md)).

---

## 1. What a DTO is

> *"An object that carries data between processes in order to reduce the number of method calls."*
> (Martin Fowler, *Patterns of Enterprise Application Architecture*)

In a Spring REST API:

```text
 Client ──JSON──▶ CreateJobRequest ──▶ Controller ──▶ use case ──▶ Job (entity) ──▶ DB
 Client ◀──JSON── JobResponse      ◀── Controller ◀── use case ◀── Job (entity)
                  └── DTOs ──┘                                     └ domain ┘
```

- A DTO has **data only**: no business rules, no persistence, no lazy loading.
- It is the **API contract**. Its fields are exactly what the client may send or will receive.
- On Java 16+, a DTO is naturally a **record**: immutable, with `equals`/`hashCode`/`toString`, and
  Jackson 3 binds it without annotations.

```java
public record CreateJobRequest(@NotBlank @Size(max = 200) String title,
                               @Positive int salary,
                               @NotNull Long companyId) {}

public record JobResponse(long id, String title, int salary, String status, CompanyRef company) {
    public record CompanyRef(long id, String name) {}
}
```

---

## 2. Why not just return the entity? Five failures (all verified)

The scratch app had a typical entity, `Job`, with getters/setters, a `LAZY` `company`, a bidirectional
`Company.jobs`, an `internalNotes` field, `featured` (a paid promotion only admins may set) and `@Version`.
`EntityJobController` used it directly as a request and response body.

### ① Lazy relation → 500

```text
GET /bad/jobs/1
HTTP/1.1 500   application/problem+json
{"detail":"Failed to write request","status":500,"title":"Internal Server Error"}

log: HttpMessageNotWritableException: Could not write JSON: Could not initialize proxy [demo.Company#1] - no session
```

The transaction ended in the service. Jackson then called `getCompany().getName()` on an uninitialized
Hibernate proxy. Loading `company` eagerly only moves the problem:

```text
GET /bad/jobs/1/with-company → 500
log: Could not write JSON: Cannot lazily initialize collection of role 'demo.Company.jobs' with key '1' (no session)
```

### ② Bidirectional relation → a *truncated* 200

With everything fetched (`join fetch j.company c join fetch c.jobs`):

```text
GET /bad/jobs/1/deep
HTTP/1.1 200   application/json
{"title":"Java Developer",…,"company":{"name":"Acme","id":1,"jobs":[{"title":"Java Developer",…,"company":{"name":"Acme",…

curl: 14027 bytes, ends with  …]}}]}}]}}   ← cut off, invalid JSON
log: Response already committed. Ignoring: HttpMessageNotWritableException:
     Document nesting depth (501) exceeds the maximum allowed (500, from `StreamWriteConstraints.getMaxNestingDepth()`)
```

`Job → Company → jobs → Job → …` loops forever. Jackson 3 stops at **500 levels of nesting**, but by
then the **status 200 and part of the body were already sent**. The client gets a "successful" response
with broken JSON. That's worse than an error, because monitoring sees 200s.

### ③ Data leaks

Every getter is serialized: `internalNotes` ("Candidate X lowballed; budget max 110k"), `version`, and
any field added in the future, unless someone remembers `@JsonIgnore` on each one. **The default is to leak.**

### ④ Overposting (mass assignment)

```text
POST /bad/jobs
{"title":"Intern","salary":1000,"company":{"id":1},
 "featured":true,"status":"CLOSED","internalNotes":"hacked"}

stored: id=4 title=Intern status=CLOSED featured=true internalNotes=hacked version=0 company=1
```

The client set **`featured=true`** (a paid feature), the status, and recruiter-only notes. Jackson binds
every property the entity has a setter or constructor parameter for. This is a classic security
vulnerability (OWASP API Security Top 10: *Broken Object Property Level Authorization*).

### ⑤ Coupling

The JSON field names **are** the entity field names. Renaming `salary` to `salaryMinor` in the database
model silently changes the public API and breaks every client.

**With DTOs, all five disappear:** the response contains only what you mapped, the request contains only
what a client may set, and nothing lazy ever reaches Jackson.

---

## 3. The DTO version (verified)

```java
@RestController @RequestMapping("/good/jobs")
class DtoJobController {
    @GetMapping("/{id}")
    JobResponse get(@PathVariable long id) { return toResponse(service.withCompany(id)); }

    @PostMapping
    ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest req) {
        var saved = toResponse(service.post(req.title(), req.salary(), req.companyId()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(saved.id()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    static JobResponse toResponse(Job j) {
        return new JobResponse(j.getId(), j.getTitle(), j.getSalary(), j.getStatus().name(),
                new JobResponse.CompanyRef(j.getCompany().getId(), j.getCompany().getName()));
    }
}
```

```text
GET /good/jobs/1
200 {"id":1,"title":"Java Developer","salary":90000,"status":"OPEN","company":{"id":1,"name":"Acme"}}

POST /good/jobs {"title":"Kotlin Developer","salary":95000,"companyId":1}
201  Location: http://localhost:18089/good/jobs/4
     {"id":4,"title":"Kotlin Developer","salary":95000,"status":"OPEN","company":{"id":1,"name":"Acme"}}
```

- No `internalNotes`, no `version`, and the company is a small reference rather than the whole graph.
- The client **can't** send `featured`, `status` or `id`: `CreateJobRequest` doesn't have them.
- `201 Created` + `Location` header (note 16 conventions).

---

## 4. Request DTOs ≠ response DTOs

| | Request DTO (`CreateJobRequest`) | Response DTO (`JobResponse`) |
|-|----------------------------------|------------------------------|
| Contains | Only what the **client may set** | What the **client may see** |
| Never contains | `id`, `status`, `featured`, `createdAt`, `version` | Secrets, internal notes, other users' data |
| Validation | Bean Validation (`@NotBlank`, `@Positive`) + `@Valid` | None needed |
| Per use case | Often separate: `CreateJobRequest`, `UpdateJobRequest`, `CloseJobRequest` | Often per view: `JobResponse`, `JobSummaryResponse` |

**Don't reuse one DTO for both directions.** Server-controlled fields would become client-settable,
and you're back to overposting.

---

## 5. Where DTOs live in Job Finder's layers

```text
 api            CreateJobRequest ─┐                 ┌─▶ JobResponse
 (DTOs live     Controller        │ map to params    │ map from entity
  here)                           ▼ or a command     │
 application    PostJobUseCase.post(title, salary, companyId) → Job
 domain         Job, Company (entities, rules)
```

- **Request DTOs and response DTOs belong to `api`.** They're the HTTP contract.
- **The application layer never sees API DTOs.** Controllers pass plain parameters or a command record.
  This is already enforced: ArchUnit forbids any layer from depending on `api` (note 19).
- Mapping entity → response happens in `api`, in a private method or a static factory on the record
  (`JobResponse.from(job)`). `api → domain` is allowed for exactly this reason.

### ⚠️ Map where the data is loaded (verified)

```java
@GetMapping("/{id}/lazy")
JobResponse lazy(@PathVariable long id) { return toResponse(service.plain(id)); }   // company not fetched
```

```text
500   log: LazyInitializationException: Could not initialize proxy [demo.Company#1] - no session
```

With `open-in-view=false` (production setting, note 23 §5), the transaction is over when the controller
maps. The use case must **load everything the response needs** (`@EntityGraph(attributePaths = "company")`,
`join fetch`) or return a **read-model projection** directly (`select new …JobSummary(…)`, note 28 §4).

---

## 6. Mapping: by hand or MapStruct (verified)

### Hand-written (Job Finder's default)

Shown in §3. It's explicit, debuggable, needs no tooling, and the compiler checks it when a record changes.

### MapStruct: generated mappers

```java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface JobMapper {
    JobResponse toResponse(Job job);
    JobResponse.CompanyRef toRef(Company company);
}
```

MapStruct is an **annotation processor** (like Lombok, it needs `annotationProcessorPaths` on JDK 23+,
note 29 §2). It generated plain Java (verified, excerpt from `JobMapperImpl.java`):

```java
@Component
public class JobMapperImpl implements JobMapper {
    public JobResponse toResponse(Job job) {
        if ( job == null ) { return null; }
        …
        title = job.getTitle();
        salary = job.getSalary();
        if ( job.getStatus() != null ) { status = job.getStatus().name(); }   // enum → String
        company = toRef( job.getCompany() );                                   // nested mapping
        return new JobResponse( id, title, salary, status, company );          // works with records
    }
```

`GET /good/jobs/1/mapstruct` returned the same JSON as the hand-written mapper.

**`unmappedTargetPolicy = ERROR` is essential.** Adding a `postedBy` field to `JobResponse` without a
source broke the build:

```text
[ERROR] JobMapper.java:[6,15] Unmapped target property: "postedBy".
```

With the default policy (`WARN`), the field would silently be `null` in every response.

| | Hand-written | MapStruct |
|-|-------------|-----------|
| Tooling | None | Annotation processor + build config |
| Readability | Explicit | Generated (readable, in `target/generated-sources`) |
| Effort for big DTOs | Grows | Small |
| Safety | Compiler (constructor arity) | `unmappedTargetPolicy = ERROR` |

**Job Finder:** hand-written while mappings are small. MapStruct becomes worth it when mapping code is a
real maintenance burden ([ADR 0005](../docs/adr/0005-dtos-at-the-api-boundary.md) decision 4).

---

## 7. Validating request DTOs (verified)

```text
POST /good/jobs {"title":"","salary":-5}
400 application/problem+json
{"detail":"Invalid request content.","status":400,"title":"Bad Request"}

log: MethodArgumentNotValidException: … with 3 errors: [Field error … on field 'title': rejected value []; … NotBlank …]
```

`@Valid` + constraints on the record components work, and invalid requests never reach the use case.
**But the response doesn't say *which* fields failed.** The 3 field errors appear only in the log. A
production API returns them (e.g. an `errors` array in the Problem Details). That's the job of the planned
global `@RestControllerAdvice` (ADR 0003), the next step.

Wrong JSON types (`"salary":"lots"`) give `400 "Failed to read request"`: a parse error, before validation.

---

## 8. Unknown properties: lenient by default, strict in Job Finder (verified)

```text
POST /good/jobs {"title":"Kotlin Developer","salary":95000,"companyId":1,"featured":true,"internalNotes":"x"}
201  (featured and internalNotes silently ignored)
```

**Spring Boot configures Jackson to ignore unknown properties.** With DTOs that's *safe* (an unknown
field can't bind to anything), but a client's typo (`"salry"`) or an unsupported field is silently
dropped, and the client never learns about it.

```properties
spring.jackson.deserialization.fail-on-unknown-properties=true
```

```text
same request → 400 application/problem+json {"detail":"Failed to read request",…}
```

**Applied to Job Finder** and locked in by `RequestBodyBindingTests`. A mutation check (property commented
out) made the test fail with `Status expected:<400 BAD_REQUEST> but was:<200 OK>`.

---

## 9. Paged responses: don't serialize `PageImpl` (verified)

Returning `Page<JobResponse>` from a controller works, but:

```text
log WARN: Serializing PageImpl instances as-is is not supported, meaning that there is no guarantee about
          the stability of the resulting JSON structure!
{"content":[…],"empty":false,"first":true,"last":false,"number":0,"numberOfElements":2,
 "pageable":{"offset":0,"pageNumber":0,"pageSize":2,"paged":true,"sort":{…},"unpaged":false},
 "size":2,"sort":{…},"totalElements":4,"totalPages":2}
```

That's Spring Data's internal structure as your public contract. With
`spring.data.web.pageable.serialization-mode=via-dto`:

```json
{"content":[…],"page":{"size":2,"number":0,"totalElements":3,"totalPages":2}}
```

That's a small, documented, stable shape. **Job Finder will set this property** when Spring Data is added
(ADR 0005 decision 7).

---

## 10. Other kinds of DTOs

| Kind | Example | Note |
|------|---------|------|
| Request / response DTO | `CreateJobRequest`, `JobResponse` | This note |
| Query projection (read model) | `select new …JobSummary(j.title, c.name)` | Note 28 §4: only the needed columns, no entity |
| Command | `PostJobCommand(title, salary, companyId)` | Input to a use case when it has many parameters |
| Event payload | `JobPostedEvent(jobId, companyId, postedAt)` | Between modules (Spring Modulith events, later) |
| External API client DTO | `GithubUserResponse` | Lives in `infrastructure`, mapped to the domain |

Naming: `…Request` / `…Response` for HTTP, `…Command` for use-case input, `…Event` for events.

---

## 11. Applied to Job Finder

| Change | Verified by |
|--------|-------------|
| ArchUnit rule **`ENDPOINTS_DO_NOT_EXPOSE_DOMAIN_TYPES`**: parameters and return types of every request-mapped method, **including generics** (`ResponseEntity<List<Job>>`), must not be domain types. Private mapping helpers may still take entities | `ArchitectureRulesTests`: rejects `LeakyController` (3 violations: `get()`, `list()`, `create(Job)`), accepts the compliant fixture. Mutation check (return type checked without generics) → test failed, so the generics check is real |
| `spring.jackson.deserialization.fail-on-unknown-properties=true` | `RequestBodyBindingTests` (record binds; unknown property → 400 Problem Details); mutation check |
| [ADR 0005](../docs/adr/0005-dtos-at-the-api-boundary.md): DTOs at the API boundary | n/a |

`mvn test`: **35 tests, 0 failures** (was 30).

### Gaps found while verifying (next step)

1. Validation and binding errors don't say which field failed (§7).
2. **An unhandled exception returns Boot's legacy JSON, not Problem Details**, which contradicts ADR 0003.
   Verified with a throwaway test controller throwing `IllegalStateException`:

   ```text
   500 application/json {"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/v1/boom"}
   ```

   (The exception message was **not** leaked, which is good.) Both gaps are fixed by the global
   `@RestControllerAdvice` that ADR 0003 already plans.

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| Returning entities from controllers | Lazy-loading 500s, truncated 200s from recursion, leaked fields (verified) |
| `@RequestBody` on an entity | Overposting: clients set `featured`, `status`, notes (verified) |
| One DTO for request and response | Server-controlled fields become client-settable |
| Mapping lazy relations in the controller (`open-in-view=false`) | `LazyInitializationException` (verified); fetch in the use case |
| Passing API DTOs into the application layer | Use cases coupled to HTTP; blocked by the layer rule |
| MapStruct without `unmappedTargetPolicy = ERROR` | New DTO fields silently `null` |
| Returning `Page<T>` as-is | Unstable JSON, warning in the log (verified); use `via-dto` |
| Fixing entity exposure with `@JsonIgnore` everywhere | Opt-out security; the next new field leaks |
| `@JsonManagedReference`/`@JsonBackReference` to "fix" recursion | Hides the real problem (entities in the API) |

## Interview questions

**Q: What is a DTO and why use one in a REST API?**
A data-only object that defines what crosses the API boundary. It decouples the API contract from the
persistence model, prevents leaking internal fields and overposting, and avoids lazy-loading and recursion
problems during serialization.

**Q: What is overposting (mass assignment)?**
When a client sets fields it shouldn't (e.g. `role`, `featured`, `price`) because the request body is bound
directly onto an entity. Separate request DTOs with only client-settable fields prevent it.

**Q: Why are records a good fit for DTOs?**
They're immutable, concise, have value semantics, and are supported by Jackson, Bean Validation and JPQL
constructor expressions.

**Q: Where do you map entities to DTOs?**
In the API layer (or the use case for read models), and only after everything the response needs has been
loaded inside the transaction. With open-in-view disabled, lazy relations can't be loaded in the controller.

**Q: Manual mapping vs MapStruct?**
Manual mapping is explicit and has no tooling cost. MapStruct generates mappers at compile time and pays off
for many or large DTOs. Configure it to fail on unmapped target properties.

**Q: Should an API ignore unknown JSON properties?**
It's a design choice. Ignoring them is tolerant but hides client mistakes. Rejecting them (`400`) gives
immediate feedback. Either way, DTOs mean unknown fields can't bind to anything sensitive.

**Q: How do you return paged data?**
As a stable DTO (`content` + `page` metadata), e.g. Spring Data's `via-dto` serialization mode or your own
`PageResponse<T>` record, never `PageImpl` as-is.
