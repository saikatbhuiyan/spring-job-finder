# 17 — `@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestHeader`, `@RequestBody`

## Goal

Learn how Spring MVC **routes** an HTTP request to a method (`@RequestMapping`) and how it
**binds** each part of the request to method parameters:

```text
POST /api/v1/companies/7/jobs?notify=true          ← path + query string
X-Request-Id: abc-123                              ← headers
Content-Type: application/json
{"title":"Java Developer","salary":90000}          ← body

@PostMapping("/companies/{companyId}/jobs")
create(@PathVariable long companyId,               ← 7
       @RequestParam boolean notify,               ← true
       @RequestHeader("X-Request-Id") String rid,  ← "abc-123"
       @RequestBody CreateJobRequest body)          ← the JSON as an object
```

Every behavior below was **verified** against a running scratch Spring Boot 4.2.0 app (Spring
Framework 7.1.0, Jackson 3.1.8) with Problem Details enabled, like our project. Each output line
is `METHOD path → status body`.

Status: 🧪 verified in a demo; becomes ✅ with the first Job Finder endpoints.

---

## Part 1: `@RequestMapping`, the router

`@RequestMapping` maps requests to handler methods by **path, HTTP method, and conditions**.

### 1.1 On the class and on methods

```java
@RestController
@RequestMapping("/api/v1")                       // prefix for every method below
class C {
    @RequestMapping(path = "/ping", method = RequestMethod.GET)
    String ping() { return "pong"; }
}
```

```text
GET  /api/v1/ping  → 200 pong
POST /api/v1/ping  → 405 {"detail":"Method 'POST' is not supported.","status":405,"title":"Method Not Allowed"}
```

The path exists but not for `POST`, so Spring answers **405**, not 404.

### 1.2 Shortcut annotations (use these)

| Shortcut | Equivalent |
|----------|------------|
| `@GetMapping("/x")` | `@RequestMapping(path="/x", method=GET)` |
| `@PostMapping` | `method=POST`, create |
| `@PutMapping` | `method=PUT`, replace |
| `@PatchMapping` | `method=PATCH`, partial update |
| `@DeleteMapping` | `method=DELETE`, delete |

Use `@RequestMapping` on the **class** for the shared prefix, and the shortcuts on methods.

### 1.3 Mapping attributes (all verified)

| Attribute | Example | Effect | Verified |
|-----------|---------|--------|----------|
| `path` (several) | `@GetMapping({"/status", "/state"})` | One handler, several URLs | both → `200` |
| `produces` | `produces = TEXT_PLAIN_VALUE` | Only matches if the client **accepts** that type | `Accept: application/json` → `406` |
| `consumes` | `consumes = APPLICATION_JSON_VALUE` | Only matches requests **sending** that type | `Content-Type: text/plain` → `415` |
| `params` | `params = "mode=advanced"` | Only matches when that query parameter is present | `?mode=advanced` → advanced handler, otherwise the other one |
| `headers` | `headers = "X-Beta=true"` | Only matches when the header is present | with header → `200` |
| Wildcard | `"/files/**"` | Matches any sub-path | `/files/2026/cv/resume.pdf` → `200` |
| Regex | `"/users/{username:[a-z]+}"` | Variable must match the regex | `Alice99` → `404` |

### 1.4 ⚠️ Trailing slashes don't match

```text
GET /api/v1/ping   → 200 pong
GET /api/v1/ping/  → 404 Not Found
```

Since Spring Framework 6, `/ping` and `/ping/` are **different** URLs. Be consistent: no trailing
slashes.

### 1.5 ⚠️ Overlapping routes: a real trap (verified, then fixed)

Two routes that can match the same URL:

```java
@GetMapping("/jobs/{id}")                                         // any value
@GetMapping(value = "/jobs/export", produces = TEXT_PLAIN_VALUE)  // literal + condition
@GetMapping(value = "/jobs/beta",   headers = "X-Beta=true")      // literal + condition
```

When the literal route's **condition fails**, Spring falls back to `/jobs/{id}` and tries to turn
`"export"` into a `long`:

```text
GET /api/v1/jobs/export  (Accept: application/json) → 400 "Failed to convert 'id' with value: 'export'"
GET /api/v1/jobs/beta    (no X-Beta header)          → 400 "Failed to convert 'id' with value: 'beta'"
```

The client gets a **misleading** error: it asked for export in JSON and is told its "id" is wrong.

**Fix:** constrain the variable so it can't swallow words:

```java
@GetMapping("/jobs/{id:\\d+}")      // digits only
```

```text
GET /api/v1/jobs/export  (Accept: application/json) → 406 "Acceptable representations: [text/plain]."  ✅
GET /api/v1/jobs/beta    (no header)                 → 404                                           ✅
GET /api/v1/jobs/abc                                 → 404                                           ✅
GET /api/v1/jobs/42                                  → 200 job id=42                                 ✅
```

**Production rule:** when literal sub-paths sit next to a `{variable}`, constrain the variable,
or design URLs that don't overlap.

---

## Part 2: `@PathVariable`, values in the URL path

Use it for **identifying a resource**: `/jobs/42`, `/companies/7/jobs/42`.

```java
@GetMapping("/jobs/{id}")
String byId(@PathVariable long id)                       // name taken from the parameter

@GetMapping("/companies/{companyId}/jobs/{jobId}")
String nested(@PathVariable("companyId") long cid,       // explicit name (parameter named differently)
              @PathVariable long jobId)
```

```text
GET /api/v1/jobs/42               → 200 job id=42 (long)
GET /api/v1/companies/7/jobs/42   → 200 company=7, job=42
GET /api/v1/jobs/abc              → 400 "Failed to convert 'id' with value: 'abc'"
```

- **Name matching:** `@PathVariable long id` works because Boot compiles with `-parameters`
  (note 15 §4). Use `@PathVariable("name")` when the Java name differs from the URL name.
- **Type conversion is automatic** (`String` → `long`, `UUID`, enums…). A bad value gives `400`.
- **Regex constraint:** `{username:[a-z]+}`. A non-matching value means the route doesn't match,
  so `404` (verified with `Alice99`).

**Optional path variable** (two URLs, one handler):

```java
@GetMapping({"/tags", "/tags/{tag}"})
String tags(@PathVariable(required = false) Optional<String> tag)
```

```text
GET /api/v1/tags       → 200 tag=<all>
GET /api/v1/tags/java  → 200 tag=java
```

**All variables at once:** `@PathVariable Map<String, String> vars` → `{"x":"1","y":"2"}`.

---

## Part 3: `@RequestParam`, the query string (`?key=value`)

Use it for **filtering, searching, sorting and paging** a collection:
`/jobs?keyword=java&page=2&size=50`.

```java
@GetMapping("/jobs")
String list(@RequestParam String keyword,                          // required (default)
            @RequestParam(defaultValue = "0") int page,           // optional, with default
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Optional<String> location)              // optional, no default
```

```text
GET /api/v1/jobs?keyword=java                               → 200 keyword=java page=0 size=20 location=<any>
GET /api/v1/jobs?keyword=java&page=2&size=50&location=Dhaka → 200 keyword=java page=2 size=50 location=Dhaka
GET /api/v1/jobs                                            → 400 "Required parameter 'keyword' is not present."
GET /api/v1/jobs?keyword=java&page=two                      → 400 "Failed to convert 'page' with value: 'two'"
GET /api/v1/jobs?keyword=java%20developer                   → 200 keyword=java developer   (URL-decoded)
```

### Required vs optional

| Declaration | Missing → |
|-------------|-----------|
| `@RequestParam String keyword` | **400** (required by default) |
| `@RequestParam(defaultValue = "0") int page` | `0` (`defaultValue` also makes it optional) |
| `@RequestParam Optional<String> location` | `Optional.empty()` |
| `@RequestParam(required = false) String x` | `null` |

### Multiple values, enums, dates

```java
@RequestParam List<String> skill
@RequestParam JobType type                                        // enum FULL_TIME, PART_TIME, CONTRACT
@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
```

```text
?skill=java&skill=spring        → skills=[java, spring]          (repeated key)
?skill=java,spring,sql          → skills=[java, spring, sql]     (comma-separated also works)
?type=CONTRACT                  → type=CONTRACT
?type=contract                  → 400   ⚠️ enum names are case-sensitive
?type=INTERN                    → 400   (not an enum constant)
?date=2026-11-20                → after=2026-11-20 (FRIDAY)
?date=20-11-2026                → 400   (not ISO format)
```

Use ISO dates (`yyyy-MM-dd`) in APIs. They're unambiguous, unlike `11/12/2026`.

### All parameters

```text
@RequestParam Map<String,String>      ?a=1&b=2&a=3  → {"a":"1","b":"2"}     ⚠️ keeps only the FIRST "a"
@RequestParam MultiValueMap<...>      ?a=1&b=2&a=3  → {a=[1, 3], b=[2]}     ✅ keeps all values
```

⚠️ **Don't *return* a `MultiValueMap`** from a `@RestController`. Spring writes it as
**multipart form data**, not JSON (verified). Convert it to a `Map<String, List<String>>` first.

---

## Part 4: `@RequestHeader`, HTTP headers

Use it for **metadata about the request**: correlation IDs, language, API version, client info.

```java
@GetMapping("/whoami")
String whoami(@RequestHeader("X-Request-Id") String requestId,                       // required
              @RequestHeader(value = "Accept-Language", defaultValue = "en") String lang,
              @RequestHeader("user-agent") Optional<String> userAgent)
```

```text
GET /api/v1/whoami  X-Request-Id: abc-123                       → 200 requestId=abc-123 lang=en userAgentPresent=true
GET /api/v1/whoami  x-request-id: lower-case-ok, Accept-Language: bn → 200 requestId=lower-case-ok lang=bn
GET /api/v1/whoami  (no X-Request-Id)                           → 400 "Required header 'X-Request-Id' is not present."
GET /api/v1/num-header  X-Page-Size: ten   (int parameter)       → 400 "Failed to convert 'X-Page-Size' with value: 'ten'"
```

- **Header names are case-insensitive** (HTTP standard): `x-request-id` matched `"X-Request-Id"`.
- Same rules as `@RequestParam`: required by default, plus `defaultValue`, `Optional`, and type conversion.
- **All headers:** `@RequestHeader HttpHeaders headers`, then `headers.getFirst("x-custom")`.

⚠️ **Security:** headers are **client-controlled**. Never trust a header like `X-User-Role: admin`
for authorization; identity comes from verified tokens (Spring Security, 🔜). Headers are fine
for correlation IDs, language and versioning.

> Cross-cutting headers like `X-Request-Id` are usually handled **once** in a filter (which adds them
> to every log line), not in every controller method. That's 🔜 with logging.

---

## Part 5: `@RequestBody`, the JSON body

Use it for **creating or updating** a resource: the client sends a JSON document.

```java
public record CreateJobRequest(String title, String company, int salary, List<String> skills) {}

@PostMapping(value = "/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
ResponseEntity<CreateJobRequest> create(@RequestBody CreateJobRequest req)
```

Jackson converts the JSON into the record (the reverse of note 16 §4).

```text
POST /api/v1/jobs  Content-Type: application/json
     {"title":"Java Developer","company":"Acme","salary":90000,"skills":["java","spring"]}
  → 201 {"title":"Java Developer","company":"Acme","salary":90000,"skills":["java","spring"]}

POST /api/v1/jobs  Content-Type: text/plain   (same JSON)
  → 415 "Content-Type 'text/plain' is not supported."

POST /api/v1/jobs  {"title":     (malformed JSON)
  → 400 "Failed to read request"

POST /api/v1/jobs  (empty body)
  → 400 "Failed to read request"     (a body is required by default)
```

### How Boot's Jackson treats missing and unknown fields (verified, isolated)

```text
Record: Req(String title, int salary)

{"title":"Dev","salary":1,"isAdmin":true}  → 200 {"title":"Dev","salary":1}    unknown field IGNORED
{"title":"Dev"}                            → 400                                missing PRIMITIVE int → error
{"title":"Dev","salary":null}              → 400                                null for primitive → error
{"salary":5}                               → 200 {"title":null,"salary":5}      missing String → null (!)
```

The configuration behind it, read from Boot's `JsonMapper` bean:

```text
FAIL_ON_UNKNOWN_PROPERTIES  = false   ← Spring Boot's default: tolerant of extra fields
FAIL_ON_NULL_FOR_PRIMITIVES = true    ← Jackson 3 default: primitives can't be missing
```

What this means for production:

1. **Unknown fields are ignored.** That's good for compatibility, since older servers accept newer
   clients. It's also why **DTOs protect you from "mass assignment"**: a client sending
   `"isAdmin": true` can't set anything, because the DTO has no such field. Never bind request
   JSON directly to an entity.
2. **A missing `String` silently becomes `null`.** The request "succeeds" with incomplete data. This is
   why every request DTO needs **Bean Validation**: `@NotBlank String title`, `@Positive int salary`,
   checked with `@Valid @RequestBody` (🔜 next step).
3. **Primitive vs wrapper:** `int salary` makes the field effectively required (missing → 400);
   `Integer salary` allows `null`. Choose on purpose.
4. The generic `"Failed to read request"` detail is intentional: it doesn't echo parser internals.
   Our own validation errors will give precise field-level messages.

### Optional body

```java
@PostMapping("/notes")
String note(@RequestBody(required = false) String body)
```

```text
POST /api/v1/notes  (text/plain) "plain text note" → 200 body=plain text note
POST /api/v1/notes  (no body)                       → 200 body=<none>
```

---

## Part 6: Which annotation for which data?

| Data | Annotation | Example | Typical use |
|------|------------|---------|-------------|
| **Which resource** | `@PathVariable` | `/jobs/42` | IDs, hierarchy (`/companies/7/jobs`) |
| **How to filter/page/sort** | `@RequestParam` | `/jobs?keyword=java&page=1` | Search, filters, pagination |
| **Request metadata** | `@RequestHeader` | `X-Request-Id`, `Accept-Language` | Tracing, i18n, versioning |
| **The resource's data** | `@RequestBody` | JSON `{"title":...}` | Create (POST), update (PUT/PATCH) |

**Rules of thumb:**

- Don't put IDs in query params (`/jobs?id=42`) when they identify the resource: use `/jobs/42`.
- Don't send complex data in the query string; use a body.
- `GET` requests don't have a body. Use query parameters for search criteria.
- Never put secrets (passwords, tokens) in the URL. URLs end up in logs and browser history. Use
  headers (`Authorization`) or the body.

---

## Part 7: Production checklist

1. **Constrain path variables** next to literal sub-paths (`{id:\d+}`) or avoid overlaps (§1.5).
2. **Paging parameters need limits:** `size=100000` must not load 100,000 rows. Validate a max
   (🔜 with validation and pagination).
3. **Request DTOs as records**, never entities, which protects against mass assignment.
4. **Validate everything** that arrives: `@Valid` on bodies, constraints on params (🔜).
5. **Don't trust headers** for security decisions.
6. **Consistent URLs:** plural nouns, lowercase, no trailing slash, `/api/v1` prefix.
7. **Typed parameters** (`long`, enums, `LocalDate`) let Spring reject bad input with `400` for free.
8. **ISO-8601 dates**, case-sensitive enum names documented in the API docs (or accept
   case-insensitive values with a custom converter).
9. **Errors are Problem Details** (✅ in the project), and every binding failure above already
   returns RFC 9457 JSON.

---

## Common mistakes

| Mistake | Result (verified) |
|---------|-------------------|
| `/jobs/{id}` next to `/jobs/export` with conditions, id unconstrained | Misleading `400 Failed to convert 'id'` |
| Trailing slash in the request (`/ping/`) | `404` |
| `?type=contract` for enum `CONTRACT` | `400` (case-sensitive) |
| Non-ISO date `20-11-2026` | `400` |
| `Map<String,String>` for repeated params | Silently keeps only the first value |
| Returning `MultiValueMap` from a REST method | Response written as multipart form data |
| Sending JSON as `text/plain` to a `consumes=JSON` endpoint | `415` |
| Relying on JSON fields being present without validation | Missing `String` → `null`, request "succeeds" |
| Binding the request body to an entity | Mass assignment risk |

## Interview questions

**Q: `@PathVariable` vs `@RequestParam`?**
`@PathVariable` takes values from the URL path and identifies a resource (`/jobs/42`).
`@RequestParam` takes values from the query string and filters or modifies the request (`?page=2`).

**Q: Is `@RequestParam` required by default? How do you make it optional?**
Yes (missing → 400). Make it optional with `required = false`, `defaultValue`, or `Optional<T>`.

**Q: What status do you get for a wrong HTTP method, an unsupported content type, and an unacceptable `Accept`?**
405, 415 and 406 respectively.

**Q: What does `@RequestBody` do?**
It deserialises the request body (e.g. JSON via Jackson) into a Java object using an
`HttpMessageConverter`, chosen by the `Content-Type`.

**Q: What happens with unknown JSON fields in Spring Boot?**
They're ignored (`FAIL_ON_UNKNOWN_PROPERTIES=false` by default). Fields not in the DTO can't be set.

**Q: `consumes` vs `produces`?**
`consumes` restricts the request `Content-Type` the handler accepts (else 415). `produces` restricts
the response type it can return, matched against `Accept` (else 406).

**Q: Are header names case-sensitive in `@RequestHeader`?**
No. HTTP header names are case-insensitive.

**Q: Why use `@GetMapping` over `@RequestMapping(method = GET)`?**
Shorter and clearer. `@RequestMapping` is still used at class level for a common path prefix.
