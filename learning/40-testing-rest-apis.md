# 40 — Testing a Spring Boot REST API: Every Kind of Test, and When to Use Each

## Goal

Job Finder has a lot of tests, and every one of them answers a question. "Does `PageQuery` reject
`size=500`?" needs no Spring at all. "Does a `POST` with a key answer the same thing twice, even when 16
copies arrive at once?" needs a real server. Using the wrong kind of test makes a suite **slow** (every
test starts the whole application), **blind** (mocks everywhere, so the parts never meet) or **lying**
(the test runs a configuration production doesn't have).

This note:

1. explains the vocabulary: test doubles, the test pyramid, what makes a good test;
2. goes through **every kind of test** a Spring Boot REST API uses, with a real example from this
   project or a ready-to-use example for the ones that come later (database, security, contracts, load);
3. shows how to **measure** whether tests are any good: code coverage (JaCoCo) and mutation testing
   (PIT), both now part of the build;
4. ends with **what is used in practice**, and what changed in Job Finder.

Status: ✅ **applied** ([ADR 0015](../docs/adr/0015-testing-strategy.md)). Three real bugs were found while
doing it: no `@WebMvcTest` slice test could start in this project; once it could, it accepted JSON the
application rejects; and any malformed query string (`?q=%zz`) was a logged `500` (§7).

---

## 1. Concepts

### What a test is

A test runs a piece of code with a **known input** and checks the **observable outcome**. Almost every
test has the same three parts, called **Arrange, Act, Assert** (or **Given, When, Then**):

```java
@Test
void aKeyIsExecutedOnceThenReplayed() {
    Reservation reservation = started(store.begin("key", "fp"));   // Arrange (given): a running request

    store.complete(reservation, CREATED);                           // Act (when): it finishes

    assertThat(store.begin("key", "fp"))                            // Assert (then): a retry is a replay
            .isEqualTo(new Outcome.Replay(CREATED));
}
```

The name says **what behavior** is checked (`aKeyIsExecutedOnceThenReplayed`), not which method is
called (`testComplete`). When it fails in a year, the name alone tells you which promise was broken.

### Good tests are F.I.R.S.T.

| Letter | Meaning | In Job Finder |
| --- | --- | --- |
| **F**ast | Run in milliseconds, so they're run all the time | Unit tests: ~1 ms each. The whole suite: ~20 s |
| **I**ndependent | No test depends on another's leftovers or order | Every test makes its own data; `@DirtiesContext` where a test changes shared state (`RateLimitTests`) |
| **R**epeatable | Same result every run, every machine | Fake clocks instead of real time, Awaitility instead of `Thread.sleep`, random ports |
| **S**elf-validating | Pass or fail, no human reads output | Assertions, never `System.out` to "have a look" |
| **T**imely | Written with the code, not after | Every ADR's behavior is locked in by tests in the same commit |

### Test doubles: the stand-ins

When the code under test needs a collaborator that is slow, unpredictable or not written yet, a test
replaces it with a **test double** (like a stunt double in a film). Five kinds, by Gerard Meszaros:

| Double | What it does | Example |
| --- | --- | --- |
| **Dummy** | Passed but never used | `null` for an argument the tested path ignores |
| **Stub** | Returns canned answers | `given(postings.find(7)).willThrow(new ResourceNotFoundException(...))` |
| **Fake** | A real, simpler implementation | `InMemoryIdempotencyStore` (instead of a database), a `Ticker` that is just an `AtomicLong` |
| **Mock** | Records calls so the test can **verify** them | `then(postings).shouldHaveNoInteractions()` |
| **Spy** | A real object that also records calls | `@MockitoSpyBean` |

Mockito makes stubs, mocks and spies. **Fakes are often better than mocks**: a mock only knows what you
told it, so a test with a mocked store passes even if the real store behaves differently. Job Finder's
store tests use the real store with a fake clock, and the filter tests use the real filter with the real
store.

### The test pyramid

```
                  ▲  slow, expensive, few; finds integration bugs
                 ╱ ╲
                ╱E2E╲          a deployed system, a real browser or client
               ╱─────╲
              ╱ integ- ╲       @SpringBootTest over real HTTP, real database (Testcontainers)
             ╱  ration  ╲
            ╱────────────╲
           ╱    slices    ╲    @WebMvcTest, @JsonTest, @DataJpaTest: one layer of Spring
          ╱────────────────╲
         ╱       unit       ╲  plain Java: no Spring, no I/O. Fast, many, precise failures
        ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔
```

Low in the pyramid: fast, and a failure points at one class. High: slow, but proves the parts work
together. You need both. A suite of only unit tests misses "the filter runs in the wrong order"; a suite
of only `@SpringBootTest`s takes minutes and every failure means "something, somewhere".

(Some teams prefer the "testing trophy", with integration tests as the biggest layer. For a REST API that
is mostly plumbing, that's what Job Finder has today; the shape follows the code, not a rule.)

---

## 2. The toolbox

`spring-boot-starter-webmvc-test` brings everything below; nothing else is needed for the first five
kinds of test (verified with `mvn dependency:tree`):

| Library | Version | Used for |
| --- | --- | --- |
| **JUnit Jupiter** | 6.1.3 | `@Test`, `@ParameterizedTest`, `@Nested`, lifecycle, extensions |
| **AssertJ** | 3.27 | Fluent assertions: `assertThat(x).isEqualTo(y)` |
| **Mockito** | 5.24 | Stubs, mocks, spies (`@MockitoBean` in Spring tests) |
| **JSONPath** / **JSONassert** | 3.0 / 1.5 | Asserting on JSON: `$.errors[0].code`, lenient comparison |
| **Hamcrest** | 3.0 | Matchers (older style; AssertJ is preferred here) |
| **Spring Test** | 7.1 | `MockMvc`, `MockMvcTester`, `RestTestClient`, the TestContext framework |
| **Spring Boot Test** | 4.2 | `@SpringBootTest`, slices, `ApplicationContextRunner`, `JacksonTester` |

Added on purpose: **Awaitility** (waiting for asynchronous outcomes), **ArchUnit** + **Spring Modulith**
(architecture tests), and now **JaCoCo** and **PIT** (§5).

---

## 3. Every kind of test, one by one

For each kind: what it is, when to use it, and how it looks in Job Finder.

### 3.1 Unit tests: plain Java, no Spring

**What**: create the object with `new`, call it, check the result. No application context, no server.

**When**: business rules, value objects, parsers, algorithms: anything whose behavior doesn't depend on
Spring. This is where most tests of the `domain` layer will live.

```java
class IdempotencyKeyTests {

    @ParameterizedTest
    @CsvSource({"255, true", "256, false"})
    void atMost255Characters(int length, boolean valid) {
        assertThat(IdempotencyKey.parse("k".repeat(length)) != null).isEqualTo(valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "azAZ09-_.~:+/=az",          // a bare key of exactly 16 characters, every range boundary
            "\" ~ ~ ~ ~ ~ ~ ~ ~\""})       // quoted: space (0x20) and ~ (0x7E), the printable boundaries
    void boundaryCharactersAreValid(String header) {
        assertThat(IdempotencyKey.parse(header)).isNotNull();
    }
}
```

Test at the **boundaries**: 255 and 256, not 10 and 1000. An off-by-one (`>` instead of `>=`) only
shows at the exact edge. (The second test was added after mutation testing found that no key used the
first or last character of an allowed range, §4.)

- **`@ParameterizedTest`** runs one test method with many inputs; each input is reported separately.
  Sources: `@ValueSource`, `@CsvSource` (several columns; `textBlock` keeps tables readable),
  `@MethodSource` (a method returning arguments), `@EnumSource`.
- **Time**: never call `Instant.now()` or `System.nanoTime()` inside code you want to test. Inject a
  clock (`Clock`, or Caffeine's `Ticker`) and give the test a fake one. `InMemoryIdempotencyStoreTests`
  "waits" 24 hours with `nanos.addAndGet(...)`, in a microsecond.
- **Exceptions**: `assertThatExceptionOfType(X.class).isThrownBy(() -> ...)`, or
  `assertThatThrownBy(...).hasMessageContaining(...)`.

In this project: `IdempotencyKeyTests`, `InMemoryIdempotencyStoreTests`, `SearchTextTests`,
`RateLimitFilterTests`.

### 3.2 Unit tests with mocks (Mockito)

**What**: the class under test is real; its collaborators are Mockito mocks.

**When**: the class's job is to **coordinate** others, and the interesting outcome is what it asked them
to do. Typical: a use case (`application` layer) that loads, decides, saves and publishes an event.

```java
@ExtendWith(MockitoExtension.class)               // creates the @Mock fields, checks unused stubs
class ApplyForJobTests {

    @Mock JobRepository jobs;
    @Mock ApplicationEventPublisher events;
    @InjectMocks ApplyForJob applyForJob;         // the real class, constructed with the mocks

    @Test
    void applyingToAClosedJobIsRejectedAndNothingIsSaved() {
        given(jobs.findById(7L)).willReturn(Optional.of(closedJob(7L)));        // stub

        assertThatExceptionOfType(JobClosedException.class)
                .isThrownBy(() -> applyForJob.apply(7L, "seeker-1"));

        then(jobs).should(never()).save(any());                                // verify
        then(events).shouldHaveNoInteractions();
    }
}
```

(This is how the Job module's use cases will be tested; the classes don't exist yet.)

**When not to mock**:

- **Values and simple objects**: never mock a record, a `List`, a `PageQuery`. Create the real thing.
- **Types you don't own** (`JdbcTemplate`, `HttpClient`, `EntityManager`): a mock of them encodes *your
  assumption* of how they behave, and the test passes while production fails. Test the adapter against
  the real thing (Testcontainers, §3.7) and mock *your* interface (`JobRepository`) above it.
- **Everything**: a test with five mocks tests the wiring, not the behavior; every refactoring breaks it.

`BDDMockito` (`given(...).willReturn(...)`, `then(mock).should()...`) is the same Mockito with names that
read as Given/When/Then.

### 3.3 Configuration tests: `ApplicationContextRunner`

**What**: starts a tiny application context with only the configuration you name, with chosen
properties, in milliseconds. No server, no component scan.

**When**: `@ConfigurationProperties` defaults and validation, conditional beans
(`@ConditionalOnProperty`), "does this misconfiguration fail startup with a clear message?".

```java
private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(IdempotencyConfig.class);

@Test
void nonsensicalValuesFailStartup() {
    runner.withPropertyValues("jobfinder.idempotency.key-ttl=30s")
            .run(context -> assertThat(context).hasFailed());
}
```

A full `@SpringBootTest` per bad value would cost a second each, and a failed context is cached as
failed. In this project: `CorsPropertiesTests`, `RateLimitPropertiesTests`, `IdempotencyPropertiesTests`,
`PageQueryTests`.

### 3.4 JSON slice: `@JsonTest`

**What**: starts only Jackson, configured **exactly as the application** (properties, customizers,
modules), and gives you a `JacksonTester<T>` to write and read your DTOs.

**When**: pinning down the wire format: date formats, property names, `null` handling, rejected input.
The fastest test of "what does the client actually see?".

```java
@JsonTest
class JsonSliceTests {

    @Autowired JacksonTester<JobResponse> response;

    @Test
    void datesAreWrittenAsIso8601StringsNotNumbers() throws Exception {
        JobResponse job = new JobResponse(42, "Java developer", Instant.parse("2026-11-25T08:30:00Z"), ...);

        assertThat(response.write(job))
                .extractingJsonPathStringValue("$.createdAt").isEqualTo("2026-11-25T08:30:00Z");
    }
}
```

In this project: `JsonSliceTests` (new): ISO-8601 dates, `null` properties written, no type coercion,
unknown properties rejected.

### 3.5 Controller slice: `@WebMvcTest` + `MockMvcTester` + `@MockitoBean`

**What**: starts **only the web layer** (controllers, `@ControllerAdvice`, `WebMvcConfigurer`s,
converters, Jackson, validation), no server, no use cases, no database. Requests go through the
`DispatcherServlet` **in memory**. The use cases the controller calls are `@MockitoBean`s.

**When**: every controller. It checks what only the web layer decides: URL and version mapping, request
binding, validation, status codes, headers, the JSON shape, how exceptions become Problem Details, and
that invalid input **never reaches the use case**.

```java
@WebMvcTest(PostingController.class)       // only this controller (plus the shared web plumbing)
@Import(PostingController.class)           // only because this controller is nested in the test class:
class WebMvcSliceTests {                   // scanning skips test-nested classes; a module's controller doesn't need it

    @Autowired MockMvcTester mvc;          // AssertJ-style MockMvc
    @MockitoBean Postings postings;        // the use case: a Mockito mock, registered as a bean

    @Test
    void createReturns201WithLocationAndCallsTheUseCaseOnce() {
        given(postings.publish("Java developer", 2)).willReturn(new Posting(42, "Java developer", 2));

        assertThat(mvc.post().uri("/api/v1/slice-probe/postings").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Java developer", "openings": 2}"""))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/api/v1/slice-probe/postings/42")
                .bodyJson().isLenientlyEqualTo("""
                        {"id": 42, "title": "Java developer", "openings": 2}""");

        then(postings).should().publish("Java developer", 2);
    }

    @Test
    void invalidRequestNeverReachesTheUseCase() {
        assertThat(postWithKey("""
                {"title": " ", "openings": 0}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);

        then(postings).shouldHaveNoInteractions();     // only a mock can prove this
    }
}
```

`@MockitoBean` (Spring Framework 6.2+) replaces Spring Boot's old `@MockBean`, which Boot 4 removed.

**Four ways to send a request in a Spring test**, all in `spring-test`:

| Client | Talks to | Assertions | Use it for |
| --- | --- | --- | --- |
| `MockMvc` | `DispatcherServlet` in memory | Hamcrest (`andExpect(status().isOk())`) | Older code; same engine as `MockMvcTester` |
| **`MockMvcTester`** | `DispatcherServlet` in memory | **AssertJ** (`assertThat(...).hasStatusOk()`) | **Slice tests** (here) |
| **`RestTestClient`** | a running server (or MockMvc) | Fluent (`expectStatus().isOk()`) | **Real-HTTP tests** (here) |
| `WebTestClient` | a running server (or MockMvc) | Fluent | WebFlux apps; needs WebFlux on the classpath |

(`TestRestTemplate` still exists, in `spring-boot-resttestclient`; `RestTestClient`, new in Spring
Framework 7, is its successor and the one used here.)

**What MockMvc can't see**, because there is no server and no real HTTP:

- **No `/error` dispatch**: an exception that escapes Spring MVC isn't turned into a response by the
  container, so `ProblemDetailsErrorController` never runs.
- **Filters registered with `FilterRegistrationBean` in a `@Configuration`** aren't in the slice: no rate
  limit, no trace ID, no idempotency replay. That's by design: test them over real HTTP (§3.6).
- **No real HTTP parsing**: chunked bodies, header folding, the container's limits, and Tomcat's own
  parameter parsing (the `?q=%zz` → `500` of §7 exists only in a real server).

In this project: `WebMvcSliceTests` (new). It is the template every module's controller tests follow,
and it also **locks in** that a slice sees the same API contract as the application (§7).

### 3.6 Full integration over real HTTP: `@SpringBootTest(RANDOM_PORT)` + `RestTestClient`

**What**: starts the **whole application** on a random free port (embedded Tomcat), and sends real HTTP
requests to it.

**When**: everything that happens **around** Spring MVC (servlet filters and their order, the error
controller, CORS preflights, rate limiting, trace IDs, idempotency replay), and end-to-end flows across
layers.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jobfinder.rate-limit.requests=3",
        "jobfinder.rate-limit.period=1m",
        "jobfinder.cors.allowed-origins=https://app.example.com"})
@AutoConfigureRestTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)   // fresh buckets per test
class RateLimitTests {

    @Autowired RestTestClient client;

    @Test
    void requestsOverTheLimitAre429WithRetryAfterAndNeverReachTheController() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/rate-probe").exchange().expectStatus().isOk();
        }
        client.get().uri("/api/v1/rate-probe")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isBetween(1, 20))
                .expectBody()
                .jsonPath("$.title").isEqualTo("Too Many Requests");
    }
}
```

Things in Job Finder that only these tests caught (earlier notes):

- a `500` thrown in a filter bypassed Problem Details, and was logged twice (note 31);
- an exception in a filter was logged by Tomcat without the trace ID (note 38);
- a request body with no `Content-Length` (chunked) bypassed the 1 MB idempotency limit (note 39);
- 16 concurrent duplicate requests executed exactly once (note 39).

The other `webEnvironment`s: `MOCK` (the default: a mock servlet environment, use with
`@AutoConfigureMockMvc`: the whole app, but MockMvc's blind spots), `DEFINED_PORT` (avoid: two builds on
one machine collide), `NONE` (no web environment).

In this project: 12 classes, from `ApiVersioningTests` to `ValidationTests`.

### 3.7 Persistence slice: `@DataJpaTest` + Testcontainers 🔜

**What**: starts only JPA (entities, repositories, a `DataSource`), each test in a transaction that is
rolled back. With **Testcontainers**, the database is a real PostgreSQL in a throwaway Docker container.

**When**: every repository with a custom query, every migration, every constraint the code relies on.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // keep our PostgreSQL
@Testcontainers
class JobRepositoryTests {

    @Container
    @ServiceConnection                      // Boot points the DataSource at the container: no URL properties
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired JobRepository jobs;

    @Test
    void searchMatchesTitleWordsCaseInsensitively() { ... }
}
```

**Why not H2** ("an in-memory database, so much faster")? Because note 36 measured what Job Finder's
queries depend on: PostgreSQL full-text search, trigram indexes, `ILIKE`, `JOIN` paging. H2 has none of
them, or has them differently. A test against H2 tests H2. Testcontainers starts a PostgreSQL container
once per test class, or once per test run with a shared container (to be measured when it's added).

Comes with the Job module and PostgreSQL ([roadmap](roadmap.md)); then also a Failsafe phase for slow
integration tests (§6).

### 3.8 Outgoing HTTP calls: `@RestClientTest`, WireMock 🔜

When Job Finder calls another service (an email provider, a geocoder): `@RestClientTest` starts only
the `RestClient` infrastructure and a `MockRestServiceServer` that answers expected requests, or
**WireMock** runs a real HTTP stub server (closer to reality: timeouts, slow responses, broken
connections). The point to test is the bad day: `500`, timeout, garbage JSON.

### 3.9 Architecture tests: ArchUnit and Spring Modulith

**What**: tests that check the **structure** of the code, not its behavior: "domain never imports web",
"every `@RequestBody` has `@Valid`", "no module reaches into another module's internals".

```java
public static final ArchRule DOMAIN_IS_WEB_AGNOSTIC = noClasses()
        .that().resideInAPackage(DOMAIN)
        .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
        .because("business rules must not depend on the delivery mechanism (HTTP)");
```

Every rule is itself tested against code that deliberately breaks it (`archfixture/`, `modfixture/`):
a rule that passes on everything is worthless. In this project: `ArchitectureTests`,
`ArchitectureRulesTests`, `ModularityTests`, `ModularityRulesTests` (note 19).

### 3.10 Packaged-application tests

**What**: build the real jar, start it in a separate JVM, check what only the packaged app shows:
profiles, logging format, what's excluded from the jar.

In this project: `ProfileConfigurationTests` starts the app per profile in a child JVM and checks dev
logs vs ECS JSON, DevTools absent in prod (notes 20, 21). The slowest class (7.3 s in the last run).

### 3.11 Concurrency and asynchronous tests

**Concurrency**: start N threads, release them at once with a `CountDownLatch`, and count outcomes.
`InMemoryIdempotencyStoreTests` races 16 threads, 200 times; `IdempotencyTests` sends 16 real HTTP
requests at once. A concurrency bug shows up *sometimes*, so repeat.

**Async**: never `Thread.sleep(500)` ("probably done by then"): it is slow when the work is fast, and
flaky when the machine is slow. Wait for the condition, with a timeout:

```java
// GlobalExceptionHandlingTests: the metric is recorded after the response, on another thread.
await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(meters.find("http.server.requests").tags(
                "uri", "/api/{version}/error-probe/fails-mid-stream", "status", "200",
                "exception", "HttpMessageNotWritableException").timer()).isNotNull());
```

### 3.12 Security tests 🔜

With Spring Security: `@WithMockUser(roles = "EMPLOYER")` for slices, `jwt()` request post-processors,
and real-HTTP tests that every endpoint without a token gets a `401` (an ArchUnit-style "deny by
default" check is common: list all endpoints, assert each is protected unless allow-listed).

### 3.13 Contract tests 🔜

A **contract test** checks that the API still matches what its clients expect.

- **Consumer-driven** (Pact, Spring Cloud Contract): the client team writes down the requests they send
  and the responses they rely on; the API's build runs them. Worth it when separate teams own client and
  server.
- **Specification-driven**: the OpenAPI document is the contract; tests check every response validates
  against it. Comes with the OpenAPI step of the roadmap.

### 3.14 Performance and load tests 🔜

**Gatling**, **k6** or **JMeter** send thousands of requests against a deployed instance and measure
latency percentiles (p50, p95, p99) and throughput. They answer "how many users?", not "is it correct?",
so they run separately (nightly, or before a release), never in `mvn test`.

### 3.15 End-to-end tests

The deployed system, from the outside: a real client against a real environment (staging), with real
database, network and configuration. Few, slow, and the only proof the deployment works. With Docker
Compose (roadmap), a smoke test that the stack starts and `/actuator/health` is `UP` is the first one.

### 3.16 Property-based tests (not adopted)

Instead of examples, describe a **property** ("for every valid key, `parse(format(k)) == k`") and let a
library generate hundreds of random inputs and shrink a failure to the smallest one. Java's library is
**jqwik**; its newest release (1.10.2) depends on JUnit Platform 1.14, while this project runs JUnit 6,
so it wasn't added (from jqwik's published pom; not tried). Parameterized tests with deliberately nasty
inputs cover the same parsers here.

---

## 4. Measuring tests: coverage and mutation testing

A test suite can be big and still not protect anything. Two tools measure it.

### Code coverage (JaCoCo)

**JaCoCo** records which lines and branches run during the tests. `mvn verify` now writes
`target/site/jacoco/index.html` and **fails below a floor**.

Measured on Job Finder (`mvn verify`, `target/site/jacoco/jacoco.csv`):

| | Lines | Branches |
| --- | --- | --- |
| Before this note's tests | 91.7 % | 82.5 % |
| After | **93.3 %** (616 of 660) | **84.4 %** (303 of 359) |
| Floor in `pom.xml` (build fails below) | 90 % | 80 % |

A **branch** is each way an `if`, `?:`, `&&` or `switch` can go; 100 % lines with 50 % branches means half
the decisions were only ever taken one way. The floor sits just below the measured value: it fails the
build when someone adds untested code, and it is raised as coverage rises, never lowered to make a build
pass.

Coverage tells you what **didn't run**: 0 % on a class means no test touched it. It does **not** tell
you a test checked anything: a test that calls every method and asserts nothing gets 100 %.

### Mutation testing (PIT)

**PIT** answers the question coverage can't: *if this code were wrong, would a test fail?* It makes a
small change (a **mutant**) to the compiled code, one at a time:

```
if (key.length() >= MIN_LENGTH)   →   if (key.length() > MIN_LENGTH)     (boundary)
return values.size() == 1 ? ...   →   return values.size() != 1 ? ...    (negated condition)
store.release(reservation);       →   (call removed)                      (void method call)
return true;                      →   return false;                       (return value)
```

then runs the tests that cover that line. A failing test **kills** the mutant (good). If every test
still passes, the mutant **survived**: that line runs in the tests, but nothing checks what it does.

This is the automated form of the manual **mutation check** every guard test in this project gets
("break the code, see the test fail, restore it").

**Job Finder before this note's tests** (`mvn clean -Pmutation verify`, 2 min 43 s on 4 threads):

```
>> Line Coverage (for mutated classes only): 580/636 (91%)
>> Generated 409 mutations Killed 325 (79%)
>> Mutations with no coverage 23. Test strength 84%
```

"Test strength" is the score among mutants a test actually ran: 84 % means 16 % of the covered code's
mutants changed behavior and **no assertion noticed**.

(The very first run, without `clean`, reported 437 mutants. Most likely cause: VS Code compiles into the
same `target/classes` with Eclipse's compiler (this project already met one of its stale classes there),
and its bytecode differs from `javac`'s, most visibly in records: the classes that lost mutants after
`mvn clean` were mostly records. Either way, mutation results are only comparable from a clean build.)

Going through the survivors one by one gave three kinds:

**1. Real gaps (fixed).** Examples:

| Survivor | Why no test failed | Test added |
| --- | --- | --- |
| `UnknownQueryParameterInterceptor.preHandle` returns `false` | The test asserted only `200`; a request stopped by an interceptor is also an empty `200` | Assert the body the handler returns |
| `size < 1` → `size <= 1` | No test used `size=1` | `size=1` and `size=100` accepted |
| `body.length > max` → `>=` (twice) | No test sent exactly the limit | 1024 bytes accepted, 1025 rejected, with and without `Content-Length` |
| The fingerprint's `query != null` negated | No test varied the query string | Same key, `?channel=app` then `?channel=web` → `422` |
| `count("too_large")` removed (and 3 other outcomes) | Only the `replayed` metric was checked | Every outcome's counter, as a delta |
| `c >= 'a'` → `c > 'a'` in the key parser | No key used the first/last character of a range | `azAZ09-_.~:+/=az`, and `" "`/`~` in a quoted key |
| `storedResponses()` returns `0` | The test said "fewer than 10" (0 is fewer than 10) | `isBetween(1, 9)` |
| Global (object-level) errors never added | No test had a constraint on the whole body | `@CompletePeriod` on a request record, by both validation paths |

Coverage also showed that a constraint on a `@PathVariable` or a `@RequestHeader` (the most common
constraints in a REST API) had **never run** in any test. The behavior was right; now it is locked in.

**2. Equivalent mutants (accepted).** The mutant can't change behavior: `unquote` returning `""` instead
of `null` (both make the key invalid), and a replay without `setContentLength`, because Tomcat adds
`Content-Length` itself to a response that fits its 8 KB buffer (checked by applying it by hand).

**3. False survivors (PIT + Spring).** PIT doesn't start a JVM per mutant: it swaps the mutated class
into a running test JVM, and Spring's test **context cache** keeps the application context alive across
mutants. So anything the context computed earlier survives the swap:

- `UnknownQueryParameterInterceptor` caches each handler's parameter names in a map, so 27 mutants
  in the computing code were never executed again (the one survivor in that class outside the cache, a
  parameter without a name, led to the fourth bug of §7);
- the `MeterRegistry` lives in the context, so "remove the call that records the metric" passes because
  an earlier mutant already recorded it.

Applied **by hand** (edit the source, `mvn test`), one of those "surviving" mutants failed 30 tests, and
another failed `GlobalExceptionHandlingTests`. This is why PIT's score is a **report to triage, not a
build gate** here. (Arcmutate's commercial Spring plugin targets exactly this.)

**After the fixes** (from clean; 412 mutants: the new handler of §7 added three):

```
>> Line Coverage (for mutated classes only): 594/640 (93%)
>> Generated 412 mutations Killed 348 (84%)
>> Mutations with no coverage 17. Test strength 88%
```

Of the 47 survivors left, 34 are false survivors of the kind above (27 in the parameter-name cache, 2 in
Spring's own argument-resolver cache, 5 around recorded metrics), and most of the rest are equivalent or
accepted: for example the two in the new handler's "not a 4xx" fallback, because Tomcat reported `400`
for every input measured. The 17 without coverage are binding kinds this API doesn't use (cookies, matrix
variables, multipart parts) and reader methods of the buffered request no endpoint calls.

---

## 5. Speed: the context cache

Starting a Spring context costs anything from a tenth of a second (this application, warm JVM) to many
seconds (a connection pool, Hibernate, a database to reach). The **TestContext framework caches contexts**: two test
classes with the same configuration (same annotations, properties, `@MockitoBean`s, imported classes)
share one. Anything that changes the configuration creates a new context:

- different `properties = ...` or `@TestPropertySource`;
- a different set of `@MockitoBean`s;
- a nested `@TestConfiguration` (each test class has its own);
- `@DirtiesContext` (throws the context away; `RateLimitTests` needs it because its buckets are shared state).

Measured by logging `org.springframework.test.context.cache` at DEBUG for a full run:

```
cache statistics: [DefaultContextCache@5686f018 size = 12, maxSize = 32, contextUsageCount = 1,
                   parentContextCount = 0, hitCount = 1646, missCount = 17, failureCount = 0]
```

17 contexts built (each `@SpringBootTest` class has its own probe controllers, so it needs its own), and
1,646 reuses. Started-in times from the same run:

| Context | Starts in |
| --- | --- |
| First context of the run (class loading, JIT) | 1.76 s |
| `@SpringBootTest(RANDOM_PORT)`, warm JVM | 0.12–0.25 s |
| `@WebMvcTest` slice, warm JVM | 0.18 s |
| `@JsonTest` | 0.04 s |

A surprise: **in an application this small, a slice is not faster to start than the full app.** The
cost of a full context grows with what it contains: once there's a connection pool, Hibernate and a
database to reach, the full app needs all of them and the web slice needs none. The reason for slices today is **isolation**: the controller
test controls exactly what the use case returns, and checks what it was asked.

**Not yet**: a separate Failsafe phase for slow tests (`*IT` classes run by `mvn verify`, not `mvn test`).
At ~20 s for the whole suite it would only add a second command; it comes with Testcontainers.

---

## 6. What is used in practice, and what's safe

| Kind | Used in practice? | Job Finder |
| --- | --- | --- |
| Unit tests (JUnit + AssertJ) | Everywhere; the base | ✅ parsers, stores, filters with fakes |
| Unit tests with Mockito | Everywhere, for coordinators (use cases); overused when mocking values | 🔜 with the first use case; Mockito agent set up |
| `ApplicationContextRunner` | Common for configuration and auto-configuration | ✅ every `@ConfigurationProperties` |
| `@JsonTest` | Less common; cheap insurance for a public API | ✅ wire format rules |
| `@WebMvcTest` + `MockMvcTester` | Standard for controllers | ✅ template + contract guard |
| `@SpringBootTest(RANDOM_PORT)` | Standard for cross-cutting and flows | ✅ 12 classes |
| `@DataJpaTest` + Testcontainers | Standard for persistence; H2 instead is the classic mistake | 🔜 with PostgreSQL |
| WireMock / `@RestClientTest` | Standard for outgoing calls | 🔜 with the first outgoing call |
| ArchUnit / Modulith | Growing, in teams that enforce architecture | ✅ with fixture-tested rules |
| Security tests | Mandatory once there's security | 🔜 with Spring Security |
| Contract tests (Pact, OpenAPI) | When other teams consume the API | 🔜 with OpenAPI |
| Load tests (Gatling, k6) | Before releases, separately from the build | 🔜 |
| Coverage (JaCoCo) | Nearly universal, usually as a floor | ✅ 90 % / 80 % floor |
| Mutation testing (PIT) | Less common; high value for critical code | ✅ on demand, triaged |

**What's safe**: tests that assert outcomes (not only status codes), at the boundaries, against real
implementations where possible, with deterministic time and no sleeps; slices that run the same
configuration as production; and a database test on the real database engine.

---

## 7. In this project

### Found along the way (real bugs)

1. **No `@WebMvcTest` could start.** `IdempotencyConfig` implemented `WebMvcConfigurer` to register its
   interceptor. Slices include every `WebMvcConfigurer` (with all the beans it defines), so every slice
   also got the idempotency filter, which needs a `MeterRegistry` that isn't part of the MVC slice:

   ```
   Error creating bean with name 'idempotencyFilter' defined in class path resource
   [com/shahabuddin/jobfinder/shared/api/IdempotencyConfig.class]: Unsatisfied dependency expressed through
   method 'idempotencyFilter' parameter 3: No qualifying bean of type
   'io.micrometer.core.instrument.MeterRegistry' available
   ```

   Fix: the interceptor moved to its own MVC-only class, `IdempotencyKeyConfig`. The rule: a
   `WebMvcConfigurer` holds Spring MVC configuration and nothing else.
2. **Once slices started, they accepted JSON the application rejects.** `JsonBindingConfig` (strict
   types, ADR 0009) is a plain `@Configuration`, and slices don't scan those. In the slice,
   `{"title": 123}` became `"123"`:

   ```
   COERCE 200 {"title":"123"}          (the application: 400, code InvalidType)
   ```

   Fix: Boot reads, for each slice, a list of extra configuration in
   `META-INF/spring/<slice annotation>.imports`. `src/test/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.json.AutoConfigureJson.imports`
   now lists `JsonBindingConfig`, so every slice with JSON (`@JsonTest`, `@WebMvcTest`) gets it. Test
   resources only: the application itself is unchanged.
3. **Mockito attached itself to the JVM at runtime**, which the JDK says "will be disallowed by default in
   a future release". Fix: Mockito is a declared `-javaagent` in Surefire's `argLine` (and `-Xshare:off`
   for the class-sharing warning its first mock causes; measured with and without the agent).

4. **A malformed query string was a `500`.** While adding a test for a parameter without a name
   (`?=x`), the real app answered `500 "An unexpected error occurred."` and logged an ERROR with a stack
   trace. Tomcat 11 throws `InvalidParameterException` from `getParameterValues` for a query string it
   can't parse, the first time Spring MVC binds a parameter; nothing mapped it, so the catch-all made it a
   server error. Measured over a raw socket, before and after:

   | Request | Before | After |
   | --- | --- | --- |
   | `?=x&q=java` (value without a name) | 500 | 400 |
   | `?q=%zz` (broken percent-encoding) | 500 | 400 |
   | `?q=%` | 500 | 400 |
   | `?q=%E9` (not UTF-8) | 500 | 400 |
   | 1,001 parameters (Tomcat's limit is 1,000) | 500 | 400 |
   | `?q=a&q=b` (control) | 200 | 200 |

   Anyone could fill the error logs (and page whoever watches them) with one malformed URL. Fix: a
   handler in `GlobalExceptionHandler` answers a `400` Problem Details, without Tomcat's message ("Invalid
   chunk ... with a value of [=x] ignored" quotes the raw input). Two traps on the way: `RestTestClient`
   treats the URL as a template and silently sent `?=x` as `?x`, and Java's `URI` refuses `%zz`, so
   these tests send raw bytes over a socket.

### Files

| File | What |
| --- | --- |
| `pom.xml` | JaCoCo (report + floor), Mockito agent, PIT in the `mutation` profile |
| `shared/api/IdempotencyKeyConfig.java` | New: the key interceptor, MVC-only (split from `IdempotencyConfig`) |
| `shared/api/GlobalExceptionHandler.java` | Unparseable query strings: `400`, not `500` |
| `src/test/resources/META-INF/spring/…AutoConfigureJson.imports` | New: `JsonBindingConfig` in every JSON slice |
| `WebMvcSliceTests` | New, 7: the controller slice template; slices get the API contract |
| `JsonSliceTests` | New, 10: dates, `null`s, no coercion, unknown properties |
| `ValidationTests` | +5: `@PathVariable` and `@RequestHeader` constraints, body + parameter errors together, whole-body constraints |
| `IdempotencyTests` | +5 and stronger: every metric outcome, the query string, the limit is inclusive (4 cases) |
| `IdempotencyKeyTests` | +8: boundary characters |
| `FilteringTests` | +5: unparseable query strings and too many parameters are `400` (raw socket); the `Map` endpoint really ran |
| `PaginationTests`, `InMemoryIdempotencyStoreTests`, `RateLimitFilterTests` | Boundaries, bounds from both sides |

Tests: 218 → **260**, all passing.

### Mutation checks (by hand) for the new guards

| Broken on purpose | Test that failed |
| --- | --- |
| The slice `.imports` file removed | `JsonSliceTests` (2 cases), `WebMvcSliceTests.jsonTypesAreAsStrictAsInTheApplication` |
| `IdempotencyConfig` a `WebMvcConfigurer` again | All 7 `WebMvcSliceTests` (context fails to start) |
| The key interceptor not registered | `WebMvcSliceTests.missingIdempotencyKeyOnAnIdempotentEndpointIs400` |
| Global errors not reported (both paths) | `ValidationTests.constraintOnTheWholeBodyIsReportedAtTheRoot` (2 cases) |
| The `InvalidParameterException` handler removed | `FilteringTests.unparseableQueryStringsAre400` (4 cases), `tooManyParametersIs400` |

---

## 8. Run it / verify it (real output)

The whole build, from clean, with coverage (`mvn clean verify`, 23.6 s):

```
[INFO] Tests run: 260, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (report) @ job-finder ---
[INFO] --- jacoco:0.8.15:check (check) @ job-finder ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Open `target/site/jacoco/index.html`: every class, with uncovered lines red and partly covered branches
yellow.

Mutation testing (`mvn -Pmutation verify`), after the fixes:

```
>> Line Coverage (for mutated classes only): 594/640 (93%)
>> 118 tests examined
>> Generated 412 mutations Killed 348 (84%)
>> Mutations with no coverage 17. Test strength 88%
>> Ran 1298 tests (3.15 tests per mutation)
[INFO] BUILD SUCCESS
```

2 min 36 s on 4 threads; `PIT >> WARNING : Minion exited abnormally due to TIMED_OUT` lines are
expected: a mutant that makes a loop endless is stopped and counted as killed.

Open `target/pit-reports/index.html`: each class's source with every mutant on its line, killed (green)
or survived (red).

Before the changes, the warning in every run that used a mock:

```
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future
releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation
WARNING: A Java agent has been loaded dynamically (.../byte-buddy-agent-1.18.17.jar)
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

After: none of these lines, and no "Sharing is only supported for boot loader classes" either.

Checking a "survivor" by hand (§4: the negated `if (requestParam != null)` in
`UnknownQueryParameterInterceptor`, which PIT reported as surviving):

```
$ mvn -q test -Dtest='FilteringTests,ValidationTests,PaginationTests,WebMvcSliceTests'
[ERROR] Tests run: 49, Failures: 30, Errors: 0, Skipped: 0
```

Try it yourself:

1. In `IdempotencyKey.parse`, change `key.length() >= MIN_LENGTH` to `key.length() > MIN_LENGTH` and run
   `mvn -q test -Dtest=IdempotencyKeyTests`: both cases of `boundaryCharactersAreValid` fail (each key is
   exactly 16 characters; verified: `Tests run: 30, Failures: 2`). Restore it.
2. Delete `src/test/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.json.AutoConfigureJson.imports`
   and run `mvn -q test -Dtest='JsonSliceTests,WebMvcSliceTests'`: three failures, all about strict JSON
   types. Restore it.
3. Write a slice test for a controller of your own: copy `WebMvcSliceTests`, replace the probe
   controller with yours, mock its use case with `@MockitoBean`.

---

## Common mistakes

| Mistake | Why it hurts | Instead |
| --- | --- | --- |
| `@SpringBootTest` for everything | Slow; failures say "something broke" | The lowest kind of test that can see the behavior |
| Mocking everything, including values | Tests the wiring; breaks on every refactoring; passes when the real thing differs | Real objects and fakes; mock only boundaries (use cases in controller slices, external systems) |
| Mocking types you don't own (`JdbcTemplate`, `HttpClient`) | Encodes your assumption of their behavior | Test the adapter against the real thing (Testcontainers, WireMock) |
| H2 "because it's faster" | Tests H2's SQL dialect, not PostgreSQL's | Testcontainers with the production database |
| `Thread.sleep` for async work | Slow and flaky | Awaitility: wait for the condition, with a timeout |
| `Instant.now()` in the code under test | Can't test expiry without waiting | Inject a `Clock`/`Ticker`; fake it in tests |
| Asserting nothing ("it didn't throw") | Coverage without protection | Assert the outcome; let PIT tell you what's unchecked |
| Chasing 100 % coverage | Tests of getters and `main`; no extra safety | A floor that catches untested additions, plus mutation testing |
| A `WebMvcConfigurer` that also defines unrelated beans | Every `@WebMvcTest` drags them in, and fails without their dependencies | MVC configuration in its own class (§7) |
| Slices running different configuration than production | The test passes for behavior production rejects | Register the application configuration slices need in the slice's `.imports` file; guard it with a test |
| A different `@MockitoBean` set in every class | One new context per class; a slow suite | Shared base configuration, or accept it consciously |
| `DEFINED_PORT` | Two builds on one machine collide | `RANDOM_PORT` |
| Tests that depend on order or on each other's data | Pass alone, fail together (or the reverse) | Each test makes its own data |
| Retrying flaky tests automatically | Hides real race conditions | Find the cause; flaky usually means a real timing assumption |
| Trusting every PIT survivor (or every kill) | Spring's cached contexts produce false survivors; IDE-compiled classes change the mutant set | `mvn clean` first; apply a doubtful mutant by hand |
| Testing malformed input only through a URI-building client | The client "fixes" it: `?=x` was sent as `?x`, `%zz` refused | Send the exact bytes (a raw socket) |

---

## Interview questions

1. **What is the test pyramid, and why?** Many fast unit tests, fewer slice/integration tests, few E2E.
   Low tests are fast and precise; high tests prove the parts work together. You need both.
2. **Mock, stub, fake: the difference?** A stub returns canned answers; a mock also records calls so you
   can verify them; a fake is a working, simpler implementation (an in-memory store).
3. **`@WebMvcTest` vs `@SpringBootTest`?** `@WebMvcTest` starts only the MVC layer with MockMvc, no
   server, collaborators mocked: fast, for controllers. `@SpringBootTest` starts the whole app, with
   `RANDOM_PORT` a real server: for filters, the error controller and end-to-end flows.
4. **What can't MockMvc test?** The container's `/error` dispatch, real HTTP parsing (chunked bodies),
   and filters that aren't in the slice.
5. **`@MockBean` vs `@MockitoBean`?** `@MockitoBean` is Spring Framework's replacement (6.2+);
   Spring Boot 4 removed `@MockBean`.
6. **`MockMvc` vs `MockMvcTester` vs `RestTestClient`?** Same in-memory engine for the first two
   (Hamcrest vs AssertJ); `RestTestClient` (Spring 7) talks to a real server or to MockMvc.
7. **Why Testcontainers rather than H2?** H2 isn't PostgreSQL: different SQL, no full-text search, no
   trigram indexes; tests would pass on queries that fail in production.
8. **How does Spring's test context cache work, and what breaks it?** Contexts are cached by their
   configuration; different properties, `@MockitoBean`s, imports or `@DirtiesContext` create new ones.
9. **Is 100 % coverage a goal?** No. Coverage shows what didn't run, not what was checked. Use a floor,
   and mutation testing for test quality.
10. **What is mutation testing?** The tool changes the code in small ways and checks a test fails for
    each; survivors are code that runs but whose behavior no test asserts.
11. **How do you test code that depends on time?** Inject a clock and control it from the test.
12. **How do you test asynchronous behavior?** Wait for the outcome with a timeout (Awaitility), never
    a fixed sleep.
13. **Why might a `@WebMvcTest` fail to start when the app runs fine?** The slice scans
    `WebMvcConfigurer`s, filters and controller advice; if one of them (or a bean defined in the same
    class) needs a bean outside the slice, the context fails. Found in this project (§7).
