# 39 — Idempotency Keys in Spring Boot REST APIs: Safe Retries for POST

## Goal

A job seeker clicks **Apply**. The phone is on a train; the request reaches the server, the application
is saved, but the response is lost in a tunnel. The app shows "network error". The user taps **Apply**
again. Now the employer has **two** applications from the same person.

Networks fail in ways the client can't distinguish:

```
1. request lost         client ──X        server          nothing happened      → retry is right
2. server failed        client ──────────▶ server (500)   nothing happened      → retry is right
3. response lost        client ◀──X────── server (201)   it DID happen         → retry duplicates!
4. timeout              client gives up ... server still working → may happen → retry may duplicate!
```

An **idempotency key** fixes this: the client gives each operation a unique ID and sends it with every
attempt. The server executes the operation **once** and answers every retry with the **first result**.

This note:

1. explains idempotency in HTTP and **measures** what happens without it;
2. compares every approach used in practice, with pros and cons;
3. walks through the design decisions of an idempotency-key implementation one by one (format,
   fingerprint, atomicity, what to store, concurrency, storage), each with its trap;
4. ends with **what is actually used and safe**, and Job Finder's implementation.

Status: ✅ **applied** ([ADR 0014](../docs/adr/0014-idempotency-keys.md)). Experiments: `Idem.java` (plain
Java: a real HTTP server and client, and two key stores raced by 16 threads), and Job Finder's tests.

---

## 1. Concepts

### Idempotent means "doing it twice = doing it once"

An operation is **idempotent** if repeating it has the same **effect** on the server as doing it once.
(The response may differ: a second `DELETE` can return `404`; what matters is the state.)

| Method | Safe (no change) | Idempotent | Example | Twice = once? |
|---|---|---|---|---|
| `GET` | ✅ | ✅ | read job 42 | ✅ |
| `PUT` | ❌ | ✅ | set job 42's title to "Java dev" | ✅ the title is "Java dev" either way |
| `DELETE` | ❌ | ✅ | delete job 42 | ✅ it's gone either way |
| `POST` | ❌ | ❌ | create a job | ❌ **two jobs** |
| `PATCH` | ❌ | ❌ (in general) | add 10 to the salary | ❌ **+20** |

RFC 9110 §9.2.2 defines this; HTTP clients and proxies may **automatically retry** idempotent requests,
but not `POST`.

### Delivery guarantees

| Guarantee | Meaning | How |
|---|---|---|
| at most once | never twice, sometimes never | don't retry |
| at least once | never lost, sometimes twice | retry until a response |
| **effectively once** | never lost, never twice | **retry + idempotency on the server** |

"Exactly once" over a network is impossible; *at least once delivery* plus *idempotent processing*
gives the same effect.

### Vocabulary

| Term | Meaning |
|---|---|
| **idempotency key** | a unique ID per operation, chosen by the client, sent with every attempt |
| **fingerprint** | a hash of the request (method, path, body) to detect a key reused for a *different* request |
| **replay** | answering a retry with the stored first response, without executing again |
| **claim / reservation** | marking a key "in progress" before executing, so a parallel duplicate can't also execute |

---

## 2. The problem, measured

`Idem.java` starts a real HTTP server whose `POST /orders` takes 500 ms and creates an order. The client
has a 200 ms timeout and retries up to 4 times, a typical "retry on timeout" policy:

```
retry after timeout: client attempts=4, client saw=gave up, orders created=4
```

The client **never saw a single response** and concluded the operation failed. The server created **four
orders**. Every retry policy without idempotency turns timeouts into duplicates.

---

## 3. Approaches used in practice

| Approach | How | Pros | Cons |
|---|---|---|---|
| Don't retry `POST` | the client shows "network error" | trivial | lost operations; users retry by hand anyway (double clicks) |
| **Client-generated ID + `PUT`** | `PUT /jobs/8e03978e-…` creates or replaces job with that ID | naturally idempotent; no extra storage | client must generate IDs; doesn't fit actions ("apply", "pay") or server-chosen IDs |
| **Unique business constraint** | DB unique index, e.g. one application per (user, job) | always on, cheap, survives everything | only where the business defines a natural key; the retry gets a `409`, not the original result |
| **Idempotency key header** | `Idempotency-Key: "8e03978e-…"` on any `POST`/`PATCH` | generic; retry gets the **original** response; standard (IETF draft, Stripe, Adyen, PayPal…) | server must store keys and responses; design traps (§5) |
| Conditional requests | `If-Match: "etag"` on `PUT`/`PATCH` | prevents lost updates | for updates of existing resources, not creates |
| Deduplication in messaging | consumer stores processed message IDs (inbox pattern) | same idea for queues | asynchronous flows only |

They combine: Job Finder will also have a **unique constraint** on applications (one per user and job)
once the database exists. The constraint protects the data; the key gives the client its original
response.

---

## 4. The standard: the `Idempotency-Key` header

The IETF HTTP APIs working group's draft `draft-ietf-httpapi-idempotency-key-header` (draft-07, October
2025, not yet an RFC) writes down what Stripe made popular:

```http
POST /api/v1/jobs HTTP/1.1
Content-Type: application/json
Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"

{"title":"Java developer"}
```

| Situation | Status the draft asks for |
|---|---|
| first request | process normally |
| retry after completion | **the first response** |
| key missing where the server requires it | `400`, with a link to documentation |
| the key with a **different** request | `422 Unprocessable Content` |
| retry while the first is **still running** | `409 Conflict` |

The value is a **Structured Field String** (RFC 9651): in double quotes. A UUID is recommended. The server
must publish its key format and expiry, and (security section) validate keys and reject low-entropy ones,
or attackers can guess other clients' keys.

---

## 5. The design decisions, one by one

### 5.1 Key format and validation

```java
static @Nullable IdempotencyKey parse(String header) {
    String key = header.startsWith("\"") ? unquote(header) : bare(header);
    return key != null && key.length() >= 16 && key.length() <= 255 ? new IdempotencyKey(key) : null;
}
```

- `"…"` is parsed as RFC 9651 String: printable ASCII, only `\"` and `\\` escapes, nothing after the
  closing quote (`"…";a=1` is an Item with parameters, not a key).
- Unquoted values are accepted too (Stripe's SDKs send them bare) if they use the characters of UUIDs,
  ULIDs and base64; `"k"` and `k` are the same key.
- 16–255 characters: `"1"` or `"order"` would collide between clients and are guessable.
- **A malformed key is a `400`, never ignored.** Ignoring it looks harmless but is the worst outcome: the
  client believes its retries are protected, and every retry executes again.
- Two `Idempotency-Key` headers: `400` (which one would we honour?).

### 5.2 The fingerprint: is it really the same request?

A key is only valid for the request it was first used with. Otherwise a client bug (reusing a key)
would silently return the response of a **different** operation:

```
POST /orders  key=K  {"item":"book"}  → 201 order 1 (book)
POST /orders  key=K  {"item":"pen"}   → without a fingerprint: 201 order 1 (book)  ← the pen order is lost
                                        with a fingerprint:    422 idempotency-key-reused
```

The fingerprint is SHA-256 of method, path, query string and body **bytes**. Headers are deliberately not
part of it: each attempt has its own trace ID, auth token refreshes, etc. Byte-for-byte means a retry must
resend the same bytes, which retry logic does (it resends the request object it built).

### 5.3 Atomicity: claim the key before executing (measured)

The obvious code is a race:

```java
if (!store.containsKey(key)) {        // two requests both see "absent"...
    store.put(key, IN_PROGRESS);      // ...both claim it...
    execute();                        // ...both execute
}
```

16 threads with the same key, 200 rounds, worst round:

| Store | Executions (of 16 identical requests) |
|---|---|
| check-then-act (`containsKey` + `put`) | **up to 3** |
| atomic `putIfAbsent` | **1, every round** |

The claim must be **one atomic operation**: `ConcurrentMap.putIfAbsent`, Redis `SET key value NX`, or a
database `INSERT` on a unique key.

### 5.4 Concurrent retries: `409`, then the result

While the first request runs, its key is **in progress**. A retry can't get the result yet, and must not
execute: it gets `409 Conflict` with `Retry-After: 1`. A second later it gets the stored response.

### 5.5 What to store, and what not

When the first request finishes:

| Outcome | Stored? | Why |
|---|---|---|
| `2xx` | ✅ replayed to every retry for 24 h | the operation happened; retries must not repeat it |
| `4xx` (e.g. validation) | ❌ key freed | nothing changed; the client corrects the request and may reuse the key |
| `5xx` | ❌ key freed | the transaction rolled back; a retry of a transient failure must be able to succeed |
| exception through the filter | ❌ key freed | same as 5xx |

The draft says a server SHOULD replay errors too, and Stripe stores `500`s ("a new key for a new
attempt"). That makes sense when the server can't tell whether a failure happened before or after the
change. Here an error means the transaction rolled back, so replaying it would only make the key useless
for the retry it exists for, and would turn a corrected request into a `422`. Documented as a deliberate
deviation.

What's replayed: status, the headers that **describe the result** (`Content-Type`, `Location`, `ETag`,
`Last-Modified`, `Content-Language`) and the body, byte for byte, plus `Idempotent-Replayed: true` so the
client can tell. Not per-response headers: the retry has its own `X-Trace-Id`.

### 5.6 Lifetime and memory

- Keys live **24 hours** after their response (Stripe's choice too), long enough for any retry policy,
  and published in `docs/problems.md` as the draft asks.
- Bodies of requests with a key are **buffered** (to hash them, then hand them to Spring MVC), so they
  are capped: 1 MB, `413 Content Too Large` beyond.
- Stored responses are capped at 64 MB, least recently used evicted first. **Keys of running requests are
  never evicted**: evicting one would let a concurrent duplicate execute. (Caffeine: entries of weight 0
  are exempt from size eviction.)

### 5.7 Scope: whose key is it?

The draft's security section asks for a **composite** lookup key. Once requests are authenticated, the
store key is *(user, key)*: two users can't collide, and nobody can fetch another user's stored response
by guessing a key. Until then keys are global; with random 16+ character keys that is practically safe,
and the fingerprint must match too.

Scoping by **IP address** is a trap: a phone that switches from Wi-Fi to mobile data retries from a new
IP, misses its key, and executes again.

### 5.8 Where in the request pipeline?

| Place | Pros | Cons |
|---|---|---|
| **Servlet filter** (chosen) | sees raw body and the complete response (status, headers, bytes); answers retries before Spring MVC; order relative to rate limit and security is explicit | doesn't know the handler method (annotations) |
| `HandlerInterceptor` | knows the handler and its `@Idempotent` | can't capture the response body |
| AOP aspect on controller methods | knows the handler, gets the return value | must re-serialize/deserialize the result; misses exceptions turned into responses |
| inside the use case | can share the business transaction | code in every use case; easy to forget |

So: a **filter** does the work, an **interceptor** does the `400`s (it knows `@Idempotent`).

Filter order: after `TraceIdFilter` (problems carry `traceId`), after the rate limit (a rejected request
must not claim a key), and after Spring Security (`-100`, later) so keys can be scoped by user.

### 5.9 Storage

| Store | Atomic claim | Survives restart | Several instances | Same transaction as the change |
|---|---|---|---|---|
| **In memory** (Caffeine, chosen for now) | `putIfAbsent` | ❌ | ❌ | ❌ |
| Redis | `SET NX PX` | ✅ | ✅ | ❌ (separate system) |
| **Database table** (the production target) | unique key `INSERT` | ✅ | ✅ | ✅ |

The subtle problem is the **commit window**: the use case commits, then the filter stores the response.
If the process dies between the two, the change exists but the key doesn't say so, and a retry executes
again. Only storing the key in the **same database transaction** as the change closes it: both commit or
neither. That's why the database store is the production target, with the request executed in one
transaction with its key row. Job Finder has no database yet, so it's an ADR follow-up, behind the
`IdempotencyStore` interface.

---

## 6. What is actually used, and what's safe

**Used:** idempotency keys for `POST`/`PATCH` (payment and commerce APIs made them the norm: Stripe,
Adyen, PayPal, Square), plus unique business constraints and client-generated IDs where they fit.

**Safe** means all of these:

1. The key format is published, validated, high-entropy; malformed keys are rejected, not ignored.
2. A fingerprint binds the key to one request (`422` otherwise).
3. The claim is atomic (`409` for a concurrent retry).
4. Only results of operations that happened are replayed; failures free the key.
5. Keys expire on a published schedule; memory and body sizes are bounded.
6. Keys are scoped per authenticated client.
7. In production with a database: the key row in the **same transaction** as the change; shared by all
   instances.
8. A business unique constraint as the last line of defence where one exists.

---

## 7. In this project

```
CorsFilter → observation → TraceIdFilter → RateLimitFilter → (Spring Security) → IdempotencyFilter (0)
  → DispatcherServlet → IdempotencyKeyInterceptor (400s) → controller
```

| What | Where |
|---|---|
| `@Idempotent` (key required) | `shared/Idempotent` (module API, like `@SortableBy`) |
| Key format | `shared/api/IdempotencyKey` |
| Store contract / in-memory store | `shared/api/IdempotencyStore`, `InMemoryIdempotencyStore` |
| Filter: buffer, fingerprint, claim, replay, `409`/`422`/`413` | `shared/api/IdempotencyFilter` |
| `400` for missing/malformed keys | `shared/api/IdempotencyKeyInterceptor` |
| Settings (`key-ttl`, `max-request-size`, `max-stored-size`) | `IdempotencyProperties`, `application.properties` |
| Problem writing shared with the rate limit | `shared/api/ProblemResponses` |
| CORS: `Idempotency-Key` allowed, `Idempotent-Replayed` exposed | `CorsConfig` |
| Metric `jobfinder.idempotency.requests{outcome}` | executed / replayed / in_progress / key_reused / too_large |

A controller only adds the annotation:

```java
@Idempotent
@PostMapping(version = "1")
ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) { … }
```

### Tests

| Test | Locks in |
|---|---|
| `IdempotencyTests` (20, real HTTP) | replay (same body bytes, `Location`, `Content-Type`, `Idempotent-Replayed`, own trace ID, executed once); `422` for another body or path; `400` missing/malformed (6 forms + length + two headers) on required and optional endpoints; quoted = bare; `409` + `Retry-After` while running, then the result; 16 concurrent duplicates → 1 execution; `5xx`, `4xx` and a filter exception free the key; optional key on unannotated endpoints; `413` with and without `Content-Length` |
| `InMemoryIdempotencyStoreTests` (7) | the contract; only the owner ends a reservation; expiry with a fake clock; bounded responses, running keys never evicted; 200 rounds × 16 threads → exactly one start |
| `IdempotencyKeyTests` (22) | the published format |
| `IdempotencyPropertiesTests` (5) | defaults, filter after the rate limit, invalid settings fail startup |
| `CorsTests` | preflight allows `Idempotency-Key`; `Idempotent-Replayed` exposed |

### Mutation checks

| Mutation | Failing test |
|---|---|
| check-then-act claim | `InMemoryIdempotencyStoreTests.ofManyConcurrentRequests…`, `IdempotencyTests.concurrentDuplicates…` |
| store every status | `aServerErrorFreesTheKey…`, `aClientErrorFreesTheKey…` |
| no release when an exception passes | `anExceptionFromALaterFilterFreesTheKeyToo` |
| fingerprint without body / without path | `theKeyWithADifferentRequestIs422` |
| malformed key ignored where optional | `aMalformedKeyIsRejectedNotIgnored` |
| key not required on `@Idempotent` | `anIdempotentEndpointRequiresAKey` |
| no `Idempotent-Replayed` | replay tests |
| running keys evictable | `storedResponsesAreBoundedButRunningRequestsAreNeverEvicted` |
| filter before the rate limit | `IdempotencyPropertiesTests.defaultsKeepKeysForADay` |
| no bound while reading the body | `aChunkedBodyLargerThanTheLimitIs413Too` |
| CORS doesn't allow the header | `CorsTests.preflightFromTheFrontendIsAllowedAndCached` |
| two key headers accepted | `twoKeyHeadersAreRejected` |
| no minimum key length | `aMalformedKeyIsRejectedNotIgnored`, `IdempotencyKeyTests.invalidKeys` |

### Found along the way

- **Caffeine evicts asynchronously.** The first eviction test stored ~8 MB into a 64 KB budget and found
  133 responses still there: Caffeine runs eviction on a background executor shortly after the writes.
  Production behaviour is fine (memory converges to the bound); the test now runs `cleanUp()` (pending
  maintenance) before counting, through a package-private hook like `RateLimitFilter.trackedClients()`.
- **A limit that looked tested but wasn't.** The `413` test sent a `Content-Length`, so the up-front length
  check rejected it; removing the bound on *reading* the body (which a chunked request, without
  `Content-Length`, relies on) failed no test. A second test now sends a chunked body.
- **ADR 0007 had predicted it**: "new request headers (e.g. `Idempotency-Key`) need a code change here".
  Without adding it to the CORS allowlist, a browser's preflight would have blocked every keyed request.

---

## 8. Run it / verify it (real output)

Job Finder has no `POST` endpoints yet (modules come with the database), so the replay itself is
verified by `IdempotencyTests` over real HTTP (embedded Tomcat on a random port, test-only endpoints). The
running app (`mvn spring-boot:run`) shows the parts that apply to every request:

```
22:51:38.477  INFO [restartedMain  ]   c.s.j.shared.api.IdempotencyConfig   : Idempotency keys: kept 24 h in memory (at most 64 MB of responses, requests up to 1024 KB)
```

A browser may send the header (CORS preflight):

```bash
curl -i -X OPTIONS -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' \
     -H 'Access-Control-Request-Headers: content-type, idempotency-key' localhost:8080/api/v1/jobs
```
```
HTTP/1.1 200
Access-Control-Allow-Headers: content-type, idempotency-key
```

A keyed request with a 2 MB body:

```bash
curl -i -H 'Content-Type: application/json' -H 'Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"' \
     --data-binary @big.json localhost:8080/api/v1/jobs
```
```
HTTP/1.1 413
X-Trace-Id: 765cd7ad0b3fee38c8391f89b46a4199
Content-Type: application/problem+json

{"detail":"Requests with an Idempotency-Key may have a body of at most 1048576 bytes.","instance":"/api/v1/jobs","status":413,"title":"Content Too Large","traceId":"765cd7ad0b3fee38c8391f89b46a4199"}
```

The same key twice on a path without a handler: `404 404`. The first `404` freed the key (not stored as
in progress), so the second isn't a `409`.

Tests: `mvn clean verify`: **218 tests, 0 failures** (164 before; 54 new).

---

## Common mistakes

| Mistake | Consequence |
|---|---|
| check-then-act lookup | parallel duplicates execute (3 of 16, measured) |
| no fingerprint | a reused key silently returns another operation's response |
| ignoring malformed keys | clients think retries are protected; every retry executes |
| storing `5xx` responses | the retry the key exists for can never succeed |
| keys scoped by IP address | a phone changing networks executes its retry again |
| low-entropy keys (`1`, `order-7`) | collisions between clients, guessable keys |
| unbounded bodies or stored responses | a memory exhaustion vector |
| evicting in-progress keys | concurrent duplicates execute under memory pressure |
| storing the key outside the business transaction | a crash between commit and store executes the retry again |
| replaying per-request headers (trace ID, `Date`) | the retry looks like the old request in logs |
| forgetting CORS | browsers can't send the header at all |

## Interview questions

**Q: Which HTTP methods are idempotent, and why does it matter?**
`GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE` (RFC 9110). Clients and proxies may retry them automatically.
`POST` and `PATCH` are not, so a timed-out `POST` can't be retried safely without extra work.

**Q: How do idempotency keys work?**
The client sends a unique key per operation with every attempt. The server atomically claims the key,
executes once, stores the result, and returns the stored result for retries. A different request with
the same key is a `422`; a retry while running a `409`.

**Q: Why the fingerprint?**
To detect a key reused for a different request, which would otherwise return the wrong response.

**Q: How do you make the claim race-free?**
One atomic operation: `putIfAbsent`, Redis `SET NX`, or a unique-key `INSERT`. Never check, then write.

**Q: Should you store error responses?**
Stripe does; the draft says SHOULD. If errors are guaranteed to have changed nothing (transactional
rollback), freeing the key is more useful: the retry can succeed. Either way, document it.

**Q: Where do you store keys in production?**
In the same database as the data, written in the same transaction as the change, so a crash can't leave
the change without its key. Redis works across instances but leaves that window open.

**Q: Idempotency key vs unique constraint?**
The constraint protects the data (no duplicate application, ever) but the retry gets an error. The key
returns the original response. Use both where a natural key exists.
