# 41 — OpenAPI and Swagger: Documenting a Spring Boot REST API (and Making the Docs Tell the Truth)

## Goal

A frontend developer wants to build the "search jobs" page. They need to know: which URL, which query
parameters, what the response looks like, what an error looks like, and what happens on a retry. They
could read the Java code, or ask you. Or they open a page that lists every operation, lets them send a
real request with a "Try it out" button, and generates a TypeScript client for them.

That page is **Swagger UI**, and the machine-readable file behind it is an **OpenAPI document**.

This note:

1. explains OpenAPI from scratch: what the document contains, OpenAPI vs Swagger, 3.0 vs 3.1;
2. compares the ways to produce it (spec-first, code-first, test-driven) and the tools;
3. adds **springdoc-openapi** to Job Finder and **measures** what it documents out of the box: nine ways
   the document misdescribed the API;
4. fixes each one, and two side effects of the library itself (one of them let any client bypass the
   API's JSON validation rules);
5. ends with **what is used in practice and safe**: where the docs are served, and how the document
   becomes a reviewed contract.

Status: ✅ **applied** ([ADR 0016](../docs/adr/0016-openapi-documentation.md)). The published document is
[`docs/openapi.yaml`](../docs/openapi.yaml).

---

## 1. Concepts

### OpenAPI vs Swagger

- **Swagger** was a specification for describing REST APIs, plus tools (Swagger UI, Swagger Editor,
  Swagger Codegen). In 2015 the specification was donated to the Linux Foundation and renamed
  **OpenAPI**. Swagger 2.0 is the old spec; OpenAPI 3.0 (2017) and **3.1** (2021) are the current ones.
- Today "Swagger" means the **tools** (by SmartBear), "OpenAPI" means the **specification**. "Swagger
  docs" usually means "an OpenAPI document shown in Swagger UI".

### What an OpenAPI document contains

A JSON or YAML file. The parts, with Job Finder's own (abridged from `docs/openapi.yaml`):

```yaml
openapi: 3.1.0                   # the spec version
info:                            # what API this is
  title: Job Finder API
  version: "1"
servers:                         # where it runs ("/" = the server that served this file)
  - url: /
paths:                           # every URL...
  /api/v1/jobs:
    get:                         # ...and every operation (method) on it
      summary: Search jobs
      parameters:                # query, path, header, cookie
        - name: size
          in: query
          schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
      responses:                 # every status code the operation can answer
        "200":
          content:
            application/json:
              schema: { $ref: "#/components/schemas/PagedModelJobResponse" }
        "400":
          $ref: "#/components/responses/BadRequest"
components:                      # reusable pieces, referenced with $ref
  schemas:
    Problem: { type: object, properties: { type: ..., title: ..., traceId: ... } }
  responses:
    BadRequest: { description: ..., content: { application/problem+json: ... } }
```

- **`$ref`** points at a reusable component: "the same as `#/components/schemas/Problem`". Written once,
  used by every operation.
- **Schemas** are JSON Schema: `type`, `properties`, `required`, `minimum`, `maxLength`, `enum`,
  `format: date-time`... OpenAPI **3.1** uses real JSON Schema (2020-12); 3.0 used a dialect of it
  (e.g. `nullable: true` in 3.0 is `type: [string, "null"]` in 3.1).

### What the document is used for

| Use | Tool |
| --- | --- |
| Reading and trying the API | Swagger UI, Redoc, Scalar |
| Generating clients (TypeScript, Kotlin, Swift...) | openapi-generator, openapi-typescript, orval |
| Detecting breaking changes between versions | oasdiff, openapi-diff |
| Validating requests/responses against the contract | swagger-request-validator, Prism |
| Mock servers before the backend exists | Prism, WireMock |
| API gateways (routes, request validation) | AWS API Gateway, Kong, Apigee |

So the document is not just documentation: it is a **contract** that code is generated from. A wrong
document generates wrong clients.

---

## 2. Approaches

| Approach | How | Pros | Cons |
| --- | --- | --- | --- |
| **Spec-first** (design-first) | Write `openapi.yaml` by hand, review it, then implement (often generating server interfaces) | API designed before code; frontend can start against a mock | Two sources of truth unless the server is generated from it; drift if not enforced |
| **Code-first** | Generate the document from the controllers at runtime (springdoc-openapi) | One source of truth: the code; no extra work per endpoint | The document only knows what the code *says*: conventions implemented in filters, resolvers and handlers are invisible (measured below) |
| **Test-driven** | Spring REST Docs: tests send real requests and record them as documentation snippets (`restdocs-api-spec` turns them into OpenAPI) | Documentation can't lie: an undocumented field fails the test | A test per operation and field; heavier |
| **Hand-written, never checked** | A wiki page or a YAML file nobody updates | — | Wrong within weeks |

Tools for Spring:

- **springdoc-openapi**: the standard for Spring Boot 3 and 4 (code-first). Reads Spring MVC mappings,
  Bean Validation constraints, Jackson types, and `io.swagger.v3.oas.annotations` annotations.
- **SpringFox**: the old library; unmaintained since 2020, doesn't work with Spring Boot 3. Seen in old
  tutorials: don't use it.
- **Spring REST Docs**: test-driven, Asciidoc output (OpenAPI via a third-party extension).

**Job Finder: code-first with springdoc**, because the API's conventions already live in code and
tests; plus a committed copy of the generated document, checked by a test (§7): the reviewed contract
that spec-first gives, without a second source of truth.

---

## 3. Adding springdoc: which version (measured)

```bash
curl -s https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml
```

| Version | Published | Built on | Notes |
| --- | --- | --- | --- |
| 3.2.1 | 2026-11-20 (5 days old) | Spring Boot 4.2.0 | swagger-core 3 → Jackson 3; Swagger UI with fixed DOMPurify |
| **3.2.0** | 2026-10-24 | Spring Boot 4.1.2 | swagger-core 2.2.55 → **Jackson 2**; Swagger UI's DOMPurify has 3 XSS advisories |

The project's rule is not to adopt a release younger than a few weeks (a new release's regressions
haven't been found yet). 3.2.0 isn't built for Boot 4.2, so it had to be **tried**: it starts, serves
the document, and the whole test suite stays green. Its two known costs are contained (§6) until 3.2.1
is a few weeks old:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>   <!-- -api: the document only, no UI -->
    <version>${springdoc.version}</version>
</dependency>
```

With nothing else, the app serves `/v3/api-docs` (JSON), `/v3/api-docs.yaml` and Swagger UI at
`/swagger-ui.html`, **in every environment**, and says so at startup:

```
WARN ... SpringDocAppInitializer : SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
WARN ... SpringDocAppInitializer : SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
```

---

## 4. What it documented out of the box (measured)

A probe controller written the Job Finder way (path versioning, a filter record, `PageQuery` with
`@SortableBy`, `@Idempotent`, constraints), and the document springdoc generated for it:

```
GET  /api/1/jobs          param query filter  REQUIRED {$ref: JobFilter}
                          param query page    REQUIRED {$ref: PageQuery}
                          resp 200 */*
POST /api/1/jobs          resp 200 */*
GET  /api/1/jobs/{id}     resp 200 */*
GET|PUT|POST|DELETE|OPTIONS|HEAD|PATCH /error     (the internal error controller, 7 operations)

PageQuery  {page: int, size: int, sort: {$ref: SortObject}}
SortObject {empty: boolean, sorted: boolean, unsorted: boolean}
ProblemDetail {type, title, status, detail, instance, properties: object}
```

Compared with how the API really works:

| # | The document said | The API does |
| --- | --- | --- |
| 1 | `/api/1/jobs` | Clients call `/api/v1/jobs` (ADR 0002). springdoc substitutes the bare version for `{version}`. (`/api/1/` happens to work too: Spring's parser accepts both.) |
| 2 | One **required** query parameter `filter`, an object | Separate optional parameters `q`, `status`, `minSalary`... (ADR 0011) |
| 3 | `page` is an object with a `sort` of `{empty, sorted, unsorted}` | `page` ≥ 0, `size` 1–100 (default 20), `sort` repeatable, only allowlisted properties, a default order (ADR 0010) |
| 4 | `POST` answers `200 */*` | `201` with `Location`, JSON |
| 5 | No `Idempotency-Key` | Required on `@Idempotent` endpoints, `409`/`422`/`413` (ADR 0014) |
| 6 | No error responses at all | `400` on every operation (unknown parameters are rejected everywhere), `429`, `500`, all `application/problem+json` |
| 7 | `ProblemDetail` with a `properties` member | No such member; `traceId` and `errors[]` that the schema didn't have |
| 8 | Response properties optional | Every property is always written (`null` included) |
| 9 | Title "OpenAPI definition", version "v0", server `http://localhost:59900` | — |

Why? springdoc reads **annotations and method signatures**. Everything Job Finder implements in a
filter (rate limit, idempotency), an argument resolver (`PageQuery`), an interceptor (unknown
parameters) or an exception handler (Problem Details) is invisible to it. A code-first document is
only as true as what the code *declares*.

---

## 5. Making the document tell the truth

### Properties (`application.properties`)

```properties
springdoc.api-docs.enabled=false                    # off by default; on in application-dev.properties (§6)
springdoc.swagger-ui.enabled=false
springdoc.paths-to-match=/api/**                    # the 7 operations of /error disappear
springdoc.default-produces-media-type=application/json   # instead of */*
springdoc.default-flat-param-object=true            # fixes #2: query objects as separate parameters
springdoc.writer-with-order-by-keys=true            # stable output, for the committed file (§7)
springdoc.swagger-ui.query-config-enabled=false     # §6
```

### `OpenApiConfig`: what springdoc can't see

springdoc has three extension points, all Spring beans:

| Bean | Called | Used for |
| --- | --- | --- |
| `OpenAPI` | once, as the starting document | `info`, `servers`, reusable `components` |
| `OperationCustomizer` | per operation, with its `HandlerMethod` (so it can read annotations) | conventions that depend on the handler |
| `OpenApiCustomizer` | once, on the finished document | document-wide rewrites |

The corrections, each one tested (`OpenApiTests`):

**Paths (#1)**: an `OpenApiCustomizer` rewrites `/api/1/` to `/api/v1/`.

**Paging (#3)**: `PageQuery` is bound by our own `PageQueryArgumentResolver`, so springdoc is told to
ignore the type, and the `OperationCustomizer` adds the real parameters from `@SortableBy`:

```java
static {
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(PageQuery.class);
}

// per operation, for each PageQuery parameter:
new QueryParameter().name("size")
        .schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(PageQuery.MAX_SIZE))
                ._default(PageQuery.DEFAULT_SIZE));
// sort: the allowlist as an enum, e.g. title, title,asc, title,desc, createdAt, ...
new QueryParameter().name("sort").style(Parameter.StyleEnum.FORM).explode(true)   // ?sort=a&sort=b
        .schema(new ArraySchema().items(new StringSchema()._enum(orders)));
```

The numbers come from the same constants the server uses (`PageQuery.MAX_SIZE`), so they can't drift.

**Idempotency (#5)**: on every `POST`/`PATCH`, an `Idempotency-Key` header (required if the method has
`@Idempotent`), the `409`/`413`/`422` responses, and `Idempotent-Replayed` on success.

**Errors (#6, #7)**: a `Problem` schema with the members the API really sends, and reusable responses:

```yaml
components:
  responses:
    BadRequest:       # also NotFound, Conflict, ContentTooLarge, IdempotencyKeyReused,
      description: …  # RequestInProgress, TooManyRequests, ServerError
      content:
        application/problem+json:
          schema: { $ref: "#/components/schemas/Problem" }
      headers:
        X-Trace-Id: …
```

Every operation gets `400`, `429` and `500` (they can happen to any request, by construction), and
`X-Trace-Id` on every response.

**Strict bodies**: the schemas of request bodies, and objects nested in them, get
`additionalProperties: false`, because unknown properties are a `400` (ADR 0005). Response schemas stay
open: a client must tolerate a new response property, so adding one isn't a breaking change.

### What controllers declare (#4)

Two things only the controller knows: the success status of a `ResponseEntity` (decided at runtime),
and the errors its use case raises. With the standard annotations:

```java
@Tag(name = "Jobs", description = "Job postings")
@RestController
@RequestMapping("/api/{version}/jobs")
class JobController {

    @Operation(summary = "Post a job")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "The new job's URL"))
    @Idempotent
    @PostMapping(version = "1")
    ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJob job) { ... }

    @Operation(summary = "Get a job")
    @ApiResponse(responseCode = "200", description = "The job")
    @ApiResponse(responseCode = "404", ref = "NotFound")       // the shared component
    @GetMapping(path = "/{id}", version = "1")
    JobResponse find(@PathVariable @Positive long id) { ... }
}
```

A declared `201` replaces springdoc's default `200` (verified). The cross-cutting responses are added
by `OpenApiConfig`; a controller never repeats `400`/`429`/`500` or the idempotency key.

### After

```
GET   /api/v1/doc-probe/jobs       q, status, minSalary, page (≥0), size (1–100, 20), sort (enum, default createdAt,desc)
                                   200 application/json · 400 · 429 · 500
POST  /api/v1/doc-probe/jobs       Idempotency-Key (required, 16–255) · body CreateJob (additionalProperties: false)
                                   201 (Location, Idempotent-Replayed) · 400 · 409 · 413 · 422 · 429 · 500
GET   /api/v1/doc-probe/jobs/{id}  id (> 0) · 200 · 400 · 404 · 429 · 500
```

### Not fixed: required response properties (#8)

Jackson writes every property, so in a response every property is present: `required`. But some may be
`null`, and the code doesn't say which (the DTO packages aren't `@NullMarked`). Marking them all
required would tell a generated TypeScript client that `salary: number` can never be `null`, which is
worse than optional. So it stays as it is until the DTO packages declare nullness (JSpecify), when
springdoc's nullable support can mark both correctly (ADR 0016 follow-up).

---

## 6. Environments and security

**Off by default, on in `dev`.** A production instance doesn't serve `/v3/api-docs` or Swagger UI:

- the document is a map of every operation and parameter: useful to an attacker probing the API;
- Swagger UI is a web application with its own JavaScript dependencies on your origin. 3.2.0's bundles a
  DOMPurify version with three published XSS advisories. Served in `dev` only, on a developer's
  machine, it can't reach users.

Published APIs (Stripe, GitHub) do publish their documents, but as **static files** on a docs site, not
from the production app. Job Finder's equivalent is the committed `docs/openapi.yaml` (§7).

**`query-config-enabled=false`**: Swagger UI can be told by its URL to load another document
(`/swagger-ui/index.html?url=https://evil.example/spec.yaml`), which then renders attacker-controlled
text on your origin. Off by default in Swagger UI since 4.1.3; set explicitly here, and tested on the
script the browser actually runs (springdoc writes the option into `swagger-initializer.js`, not into
`/v3/api-docs/swagger-config`: the first version of the test checked the wrong file and missed it).

```
$ curl -s localhost:8080/swagger-ui/swagger-initializer.js | grep queryConfig    # property false
$ ... --springdoc.swagger-ui.query-config-enabled=true
queryConfigEnabled: "true",
```

### Two side effects of the library (found by existing tests and measurement)

**1. A second `@ControllerAdvice`.** `GlobalExceptionHandlingTests` (ADR 0008: exactly one advice)
failed as soon as springdoc was added:

```
Expected size: 1 but was: 2 in:
[com.shahabuddin.jobfinder.shared.api.GlobalExceptionHandler@19f634ac,
    org.springdoc.core.configuration.SpringDocConfiguration$OpenApiResourceAdvice@717ca1ee]
```

springdoc's advice answers its own "group not found" exception with a 404 in its own (non-Problem)
format. Spring asks advice beans in **order**; both were at the lowest precedence:

```
ADVICE com.shahabuddin.jobfinder.shared.api.GlobalExceptionHandler order=2147483647
ADVICE org.springdoc.core.configuration.SpringDocConfiguration$OpenApiResourceAdvice order=2147483647
```

So which one was asked first depended on bean registration order. Fix: `GlobalExceptionHandler` is
`@Order(Ordered.HIGHEST_PRECEDENCE)`; first, its `Exception` catch-all answers everything, and no
library advice can take over part of the error handling. (The advice exists only while the docs are
enabled, which is why the test passed again once they were off by default: the order test runs with
them on.)

**2. A second way to read request bodies.** springdoc brings Jackson 2's YAML module (to write
`/v3/api-docs.yaml`), and Spring MVC registers a message converter for every Jackson data format it finds
on the classpath. Before and after springdoc:

```
before: ... JacksonJsonHttpMessageConverter, Jaxb2RootElementHttpMessageConverter
after:  ... JacksonJsonHttpMessageConverter, MappingJackson2YamlHttpMessageConverter [application/yaml], Jaxb2...
```

So the API now spoke YAML, through Jackson 2, which has none of the rules configured on Jackson 3:

| Request | Result |
| --- | --- |
| JSON `{"title": 123, "openings": "2", "x": 1}` | `400 InvalidType` (as intended) |
| The same as YAML (`Content-Type: application/yaml`) | **`200`**: `123` became `"123"`, `"2"` became `2`, the unknown `x` ignored |
| `Accept: application/yaml` | **`200`**, YAML, with `at: 1795595400.000000000` (an epoch number, not ISO-8601) |

Any client could bypass ADR 0005, 0009 and 0011 by changing the `Content-Type`. Fix: no Jackson 2
converter serves the API:

```java
@Configuration(proxyBeanMethods = false)
class MessageConvertersConfig implements WebMvcConfigurer {

    @Override
    @SuppressWarnings("removal")   // Jackson 2 converters are deprecated for removal in Spring 7
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters ->
                converters.removeIf(AbstractJackson2HttpMessageConverter.class::isInstance));
    }
}
```

Now a YAML body is `415 Unsupported Media Type` and `Accept: application/yaml` is `406 Not Acceptable`,
as Problem Details. springdoc's own `/v3/api-docs.yaml` still works: it returns a ready-made `String`.

The lesson: **a dependency is not just the API you call.** It can register beans, advice, converters,
filters and endpoints just by being on the classpath (that's what auto-configuration is). After adding
one, look at what changed: the bean list, the converters, the endpoints, the startup log.

---

## 7. The published contract: `docs/openapi.yaml`

Production doesn't serve the document, so where do clients get it? From the repository:
`docs/openapi.yaml`, generated from the real application and **checked by a test**:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "springdoc.api-docs.enabled=true")
@AutoConfigureRestTestClient
class OpenApiDocumentTests {

    @Test
    void theCommittedDocumentMatchesTheCode() throws IOException {
        String generated = client.get().uri("/v3/api-docs.yaml").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8)).isEqualTo(generated);
    }
}
```

- Change an endpoint, and the build fails until you run
  `mvn test -Dtest=OpenApiDocumentTests -Dopenapi.update=true` and commit the new file.
- So every pull request that changes the API **shows the contract diff**: a reviewer sees "`salary` was
  removed from `JobResponse`" in plain YAML, without reading Java.
- The output is deterministic: keys sorted (`writer-with-order-by-keys`), and a relative server URL
  (`/`) instead of `http://localhost:<random port>`.

This is "snapshot" (or "approval") testing. It doesn't decide whether a change is *allowed*; a
breaking-change check (oasdiff in CI) can be added on top of the same file.

Today the file has no `paths` (there are no feature modules yet): the info, the shared schemas and the
reusable responses. The Job module's endpoints will appear in it as a diff.

---

## 8. What is used in practice, and what's safe

| Practice | Common? | Job Finder |
| --- | --- | --- |
| springdoc-openapi for Spring Boot (code-first) | The default | ✅ |
| Spec-first with generated server interfaces | Common in API-first organisations, large teams | Not chosen: conventions live in code |
| Swagger UI in production | Common, and a frequent audit finding | ❌ dev only |
| Document generated as-is, never checked | Very common | ❌ corrected and tested |
| Committed document + diff in review | Good practice; less common | ✅ `OpenApiDocumentTests` |
| Breaking-change check in CI (oasdiff) | Growing | 🔜 with CI |
| Response validation against the document (contract tests) | Less common, high value | 🔜 (ADR 0015 follow-up) |
| Error responses documented | Often missing | ✅ every operation |

**What's safe**: a document that describes what the server does (test it by sending the requests it
describes), off in production or published as a static file, a UI that can't be pointed at other
documents, and a check after adding any library of what it registered.

---

## 9. In this project

| File | What |
| --- | --- |
| `pom.xml` | `springdoc-openapi-starter-webmvc-ui` 3.2.0, with why this version |
| `application.properties` / `application-dev.properties` | Off by default, on in `dev`; API only, JSON, flat parameter objects, sorted keys, no query config |
| `shared/api/OpenApiConfig.java` | Info, servers, `Problem`/`Violation` schemas, reusable responses; paging, idempotency, standard errors, trace header; `/api/v1` paths; strict request bodies |
| `shared/api/MessageConvertersConfig.java` | No Jackson 2 converter: bodies are JSON only |
| `shared/api/GlobalExceptionHandler.java` | `@Order(HIGHEST_PRECEDENCE)` |
| `docs/openapi.yaml` | The published contract |
| `OpenApiTests` | 11 tests: each correction, several by sending the documented request; Swagger UI option; handler order |
| `OpenApiDocumentTests` | The committed document matches the code |
| `RequestBodyBindingTests` | +2: YAML body `415`, YAML `Accept` `406` |
| `ProfileConfigurationTests` | prod: `/v3/api-docs` and `/swagger-ui.html` are `404`; dev: both served |

Tests: 260 → **274**, all passing.

### Mutation checks

| Broken on purpose | Test that failed |
| --- | --- |
| No `/api/v1` rewrite | `OpenApiTests` (6 tests: every path lookup) |
| `PageQuery` not ignored | `pagingIsDocumentedAsTheContractNotAsThePageQueryRecord` |
| `default-flat-param-object=false` | `aFilterRecordIsTheSeparateParametersClientsSend` |
| Idempotency key never required | `idempotencyKeyIsRequiredWhereTheEndpointRequiresIt` |
| No `400` response | `everyOperationDocumentsTheErrorsTheRequestPipelineCanProduce` |
| Request bodies (or nested objects) left open | `requestBodiesRejectUnknownPropertiesResponsesStayOpen` |
| `paths-to-match` removed | `onlyTheApiIsDocumented`, `OpenApiDocumentTests`, +1 |
| `@Order` removed from the handler | `ourExceptionHandlerComesBeforeTheAdviceSpringdocBrings` |
| `query-config-enabled=true` | `swaggerUiIsServedAndCantBePointedAtAnotherDocument` (after fixing the test: it first checked the JSON config, where springdoc doesn't write the option) |
| The API description changed | `OpenApiDocumentTests` |
| Docs not disabled by default | `ProfileConfigurationTests.prodWritesEcsJsonHidesHealthDetailsAndHasNoDebug` |
| YAML converter kept | `RequestBodyBindingTests` (2) |

Mutation testing (PIT, note 40) on the new classes, with a lesson of its own:

```
first run:            Generated 66 mutations Killed 0 (0%)      ← even "the OpenAPI bean is null" survived
cache disabled:       Generated 66 mutations Killed 60 (91%)
after the fixes:      Generated 64 mutations Killed 64 (100%)
```

springdoc **caches the generated document**, and PIT swaps mutants into a running test context: the
cached document was built before any mutant existed, so none of them ever ran. The tests now set
`springdoc.cache.disabled=true` (the document is regenerated per request; also better tests, since each
one checks a fresh generation). Of the 6 survivors left, one was **a real bug in `OpenApiConfig`**: the
`Idempotent-Replayed` header was added to every inline response, not just `2xx` ones, so a `404` a
controller declares inline would have claimed to be a replay. The others were missing cases (a list of
objects in a request body, a sort without a default, an endpoint that can't be sorted, anything next to a
`$ref`) and one dead null check, removed.

---

## 10. Run it / verify it (real output)

```bash
mvn spring-boot:run
```

Open `http://localhost:8080/swagger-ui.html` (redirects to `/swagger-ui/index.html`). The document:

```
$ curl -s localhost:8080/v3/api-docs.yaml | head -12
openapi: 3.1.0
info:
  description: |
    Job-search REST API. Conventions that apply to every operation:

    - **Errors** are RFC 9457 Problem Details (`application/problem+json`); compare on `type` ([catalogue](https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md)). ...
  title: Job Finder API
  version: "1"
servers:
- url: /
paths: {}
```

In the packaged app (any profile but `dev`) neither exists:

```
$ curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/v3/api-docs        # 404 (ProfileConfigurationTests)
```

The whole build, from clean (`mvn clean verify`, 26.4 s):

```
[INFO] Tests run: 274, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

And in `dev` (`mvn spring-boot:run`): started in 1.35 s with no `WARN` or `ERROR` line (springdoc's
"enabled by default" warnings are gone once the properties are explicit),
`/swagger-ui.html` → `302` to `/swagger-ui/index.html` → `200`, `/v3/api-docs` → OpenAPI 3.1.0 with the
eight shared responses.

Try it yourself:

1. Add a `@GetMapping` to any controller, run `mvn test -Dtest=OpenApiDocumentTests`: it fails and
   shows the difference. Run it with `-Dopenapi.update=true`, then `git diff docs/openapi.yaml`.
2. In the probe of `OpenApiTests`, remove `@ApiResponse(responseCode = "201", ...)` from `create`: the
   document says `200`, and two tests fail (verified): `successStatusAndNotFoundComeFromTheController` and
   `idempotencyKeyIsRequiredWhereTheEndpointRequiresIt` (it checks the `201`'s headers).
3. In `MessageConvertersConfig`, replace the `removeIf` condition with `c -> false` and run
   `mvn -q test -Dtest=RequestBodyBindingTests`: both YAML tests fail, because the YAML body is bound and
   answered `200` (verified). Restore it.

---

## Common mistakes

| Mistake | Why it hurts | Instead |
| --- | --- | --- |
| Trusting the generated document | It only knows what annotations and signatures say: filters, resolvers and handlers are invisible | Compare it with the real behavior; correct it once, centrally; test it |
| SpringFox | Unmaintained; doesn't run on Boot 3+ | springdoc-openapi |
| Swagger UI enabled in production | Attack surface, third-party JavaScript on your origin | Dev only, or a static docs site |
| `?url=` enabled in Swagger UI | A crafted link shows attacker content on your origin | `query-config-enabled=false` |
| Documenting `200` for a `201` | Generated clients and readers expect the wrong status | `@ApiResponse(responseCode = "201")` on `ResponseEntity` methods |
| No error responses in the document | Clients don't know the error format exists | Shared `Problem` schema and responses on every operation |
| `@ApiResponse(400)`, `429`, ... repeated on every method | Noise, and the one place someone forgets | An `OperationCustomizer` for cross-cutting responses |
| Marking everything `required` without knowing nullness | Generated clients assume non-null | Declare nullness first (JSpecify), then generate |
| Adding a library without checking what it registers | Hidden advice, converters, endpoints | Diff the beans, converters and endpoints; run the guard tests |
| A document nobody reviews | Breaking changes ship unnoticed | Commit the document; the diff is part of the review |
| Server URL `http://localhost:8080` in the document | Wrong everywhere else, unstable snapshots | A relative server `/` |

---

## Interview questions

1. **OpenAPI vs Swagger?** OpenAPI is the specification (3.0, 3.1); Swagger is the old name and now the
   tools (Swagger UI, Editor, Codegen).
2. **Spec-first vs code-first?** Spec-first writes the document and implements it; code-first generates
   it from the code. Spec-first designs before coding; code-first has one source of truth but only
   knows what the code declares.
3. **How does springdoc build the document?** From Spring MVC's handler mappings at runtime: method,
   path, parameters, return types, Bean Validation constraints, Jackson types, and `@Operation` /
   `@ApiResponse` / `@Schema` / `@Tag` annotations. `OpenApiCustomizer` / `OperationCustomizer` beans
   adjust it.
4. **How do you document something springdoc can't see, like a custom argument resolver?** Ignore the
   type (`SpringDocUtils.addRequestWrapperToIgnore`) and add the real parameters in an
   `OperationCustomizer`.
5. **Should Swagger UI run in production?** Usually not: it maps the attack surface and adds a web app's
   JavaScript to your origin. Publish the document as a file instead.
6. **What's `$ref`?** A reference to a reusable component (schema, response, parameter), so it's defined
   once.
7. **OpenAPI 3.0 vs 3.1?** 3.1 uses standard JSON Schema 2020-12 (`type: [string, "null"]` instead of
   `nullable: true`, `examples`, `const`...).
8. **How do you keep the document from drifting?** Generate it, commit it, fail the build when it
   differs; better still, validate real responses against it in tests.
9. **`additionalProperties: false`: on requests, responses, or both?** Requests, if the server rejects
   unknown properties; not on responses, or adding a property breaks strict clients.
10. **Why might adding a documentation library change your API's behavior?** Auto-configuration: on the
    classpath, it can register advice, message converters, filters, endpoints. springdoc here added a
    YAML converter that bypassed the JSON validation rules, and an exception-handling advice.
