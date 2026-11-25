# ADR 0014: Idempotency keys

- **Status:** Accepted; extended by [ADR 0015](0015-testing-strategy.md) (the key interceptor registered apart from the filter, so controller slice tests can start)
- **Date:** 2026-11-24
- **Extends:** [ADR 0003](0003-problem-details-for-errors.md) (two problem types),
  [ADR 0007](0007-cors-policy.md) (`Idempotency-Key` allowed, `Idempotent-Replayed` exposed)

## Context

`GET`, `PUT` and `DELETE` are idempotent in HTTP: repeating them has the effect of doing them once.
`POST` (create a job, apply for a job) and `PATCH` are not. A client whose `POST` timed out can't know
whether the server executed it, and every reasonable reaction is wrong: not retrying may lose the
operation, retrying may do it twice. Measured before deciding (`learning/39-idempotency-keys.md`):

- **Retry after a timeout**: a client with a 200 ms timeout retried a `POST` the server needed 500 ms
  for. It gave up after 4 attempts without ever seeing a response; the server had created **4 orders**.
- **Atomicity**: 16 concurrent requests with the same key against a check-then-act store
  (`containsKey`, then `put`) executed the operation **up to 3 times** (worst of 200 rounds); with an
  atomic `putIfAbsent`, exactly once in every round.

The IETF draft `draft-ietf-httpapi-idempotency-key-header` (draft-07, October 2025; not yet an RFC)
standardizes the practice Stripe and others introduced: an `Idempotency-Key` request header, a
Structured Field String (`"8e03978e-…"`); `400` for a missing key where required, `422` for a key reused
with a different payload, `409` for a retry while the first request is still being processed; the server
publishes the key format and expiry; keys must be validated and of high entropy.

## Decision

1. **`Idempotency-Key` on `POST` and `PATCH`** under `/api/**`, honoured whenever sent; mandatory on
   endpoints annotated **`@Idempotent`** (`shared` module API), which every create-like operation uses.
2. **Format**: a String Structured Field, or the same characters unquoted (widely used clients send it
   bare); 16–255 printable ASCII characters; one header. A missing key (where required) or a malformed key
   (anywhere) is a `validation-error` (`Required` / `InvalidValue`): never ignored, because the client
   believes its retries are protected.
3. **Fingerprint**: SHA-256 of method, path, query and body bytes. The same key with a different request
   is **`422 idempotency-key-reused`**. Headers are not part of it (a retry has its own trace ID).
4. **Atomic claim** of the key before execution; a retry during execution is **`409 request-in-progress`**
   with `Retry-After: 1`.
5. **Only 2xx responses are stored** and replayed: status, describing headers (`Content-Type`,
   `Location`, `ETag`, `Last-Modified`, `Content-Language`) and body, plus `Idempotent-Replayed: true`. Any
   other outcome **frees the key**: an error means the change didn't happen (its transaction rolled back),
   so the retry or the corrected request must be able to run. This deviates from the draft's SHOULD to
   replay errors, deliberately: replaying a transient `500` would make the key useless for the retry it
   exists for, and replaying a `400` would turn the corrected request into a `422`.
6. **Lifetime 24 hours** after the response, published in `docs/problems.md`.
7. **Bounded memory**: request bodies with a key are buffered up to 1 MB (`413` beyond it); stored
   responses up to 64 MB, least recently used evicted first; keys of running requests are never evicted.
8. **Where**: a servlet filter (`IdempotencyFilter`) at order `0`, after the trace ID filter and the rate
   limit (a rejected request doesn't claim a key) and after Spring Security's chain (`-100`) once added
   (keys will be scoped per user); an interceptor for the `400`s, since only Spring MVC knows the handler
   and its annotation.
9. **Storage** behind `IdempotencyStore` (atomic begin / complete / release). **In memory** (Caffeine) for
   now: one instance, no database yet. Keys don't survive a restart.
10. Locked in by `IdempotencyTests` (20, real HTTP, incl. 16 concurrent duplicates and a chunked body),
    `InMemoryIdempotencyStoreTests` (7, incl. 200 rounds of 16 threads, expiry with a fake clock, eviction),
    `IdempotencyKeyTests` (22), `IdempotencyPropertiesTests` (5), `CorsTests`. Mutation checks: a
    check-then-act claim, storing errors, not releasing on an exception, a fingerprint without body or
    path, ignoring malformed keys, not requiring keys, no replay marker, evictable running keys, the
    filter before the rate limit, no body limit, the header not allowed by CORS, no bound while reading
    a chunked body, accepting two key headers, and no minimum key length each fail the test that guards
    it.

## Consequences

**Positive**
- A client can retry any create safely after a timeout or a dropped connection, and double submits
  (double clicks, parallel retries) execute once.
- One mechanism for every module: an annotation, no per-endpoint code.
- Standard header and errors; a documented contract clients can rely on.

**Negative / accepted trade-offs**
- **Per instance and lost on restart**: a retry reaching another instance, or arriving after a restart,
  executes again.
- Between the use case's commit and the filter storing the response there is a window: a failure there
  (e.g. the response can't be written) frees the key although the change was committed.
- Keys are global until requests are authenticated: two clients who chose the same key would see each
  other's conflicts (with random 16+ character keys, practically never), and a replay is served to anyone
  who presents the key and the identical request.
- Byte-for-byte fingerprints: a retry that re-serializes the JSON differently (key order, whitespace) is
  a `422`. Retries resend the same bytes in practice.
- Asynchronous handlers (`DeferredResult`, `Callable`) aren't supported: their key is freed, not stored.
- A request with a key costs one body copy (≤ 1 MB) and one SHA-256.

**Follow-ups**
- **With PostgreSQL** (before a second instance): a JDBC `IdempotencyStore` with a unique constraint on
  (scope, key), and the request executed in **one transaction** with the key's row, so the change and its
  stored response commit or roll back together (closes the restart, multi-instance and commit-window gaps).
  Expired rows purged by a scheduled job.
- **With Spring Security**: scope keys by the authenticated user (the draft's "composite key").
- An ArchUnit rule if create endpoints start appearing without `@Idempotent`.

## References

- draft-ietf-httpapi-idempotency-key-header-07; RFC 9651 (Structured Field Values) §3.3.3 String;
  RFC 9110 §9.2.2 (idempotent methods)
- Stripe API: idempotent requests
- Learning note: `learning/39-idempotency-keys.md`
