# ADR 0012: Rate limiting

- **Status:** Accepted
- **Date:** 2026-11-24
- **Extends:** [ADR 0007](0007-cors-policy.md) (`Retry-After` is an exposed header)

## Context

Without a limit, one client (a buggy script, a scraper, an attacker) can use the whole capacity of the
API and its database. Measured before deciding (`learning/37-rate-limiting.md`):

**Algorithms** (simulation, 10 requests / 60 s):

| Algorithm | Boundary burst (10 + 10 around a window edge) | Compliant client (1 per 6 s) | State per client |
|---|---|---|---|
| Fixed window | **20 allowed in < 2 s** | 100 / 100 | 2 numbers |
| Sliding log | 10 | 100 / 100 | up to 10 timestamps |
| Sliding window counter | 11 | **95 / 100** (approximation rejected 5 compliant requests) | 3 numbers |
| Token bucket | 10 | 100 / 100 | 2 numbers |
| Leaky bucket (queue) | 11, **delays up to 59 s** | 100 / 100 | 1 number + queued requests |
| GCRA | 10 | 100 / 100 | 1 timestamp |

**Implementation risks**
- A counter without atomic updates allowed **564 to 1014** of a limit of 100 (16 threads).
- **Two instances** with in-memory buckets allowed **20** of a limit of 10 behind a round-robin balancer;
  a shared Redis bucket (Bucket4j + Lettuce) allowed exactly 10, also under contention. Redis cost ~0.45 ms
  per check (local Docker) vs ~0.4 µs in memory; with Redis stopped every check threw a `TimeoutException`
  after the full timeout.
- **Client identity**: `X-Forwarded-For` rotated by the client bypassed the limit with
  `server.forward-headers-strategy=framework`, and with `native` using Tomcat's default trusted proxies
  (loopback and private ranges). With `native` and `internal-proxies` limited to the real proxy, forged
  values were ignored, and Tomcat took the rightmost untrusted address (`203.0.113.9, 198.51.100.7` →
  `198.51.100.7`).

## Decision

1. **Token bucket per client**, via **Bucket4j** (`bucket4j_jdk17-core` 8.22.0): bursts up to the
   capacity, then a steady average; lock-free and exact; the same library supports Redis when needed.
   Default **60 requests, refilled over 1 minute** (`jobfinder.rate-limit.*`, validated at startup).
2. **Client = `HttpServletRequest.getRemoteAddr()`**, never a header read by our code. Behind a reverse
   proxy, the deployment sets `server.forward-headers-strategy=native` **and**
   `server.tomcat.remoteip.internal-proxies` to the proxy's addresses only; the proxy must append (not
   pass through) `X-Forwarded-For`. Once authentication exists, authenticated requests are keyed by user.
3. **Bounded memory**: buckets in a Caffeine cache (at most 100 000 clients, dropped after one idle
   period, when they'd be full again anyway).
4. **`/api/**` only.** Actuator (health probes) is never limited: a throttled liveness probe would get a
   healthy instance restarted.
5. **Over the limit: `429 Too Many Requests`**, `Retry-After` in seconds (RFC 9110), Problem Details
   body, written by the filter itself (no Spring MVC dispatch: rejecting stays cheap under load).
   `Retry-After` is a CORS-exposed header.
6. **Filter order**: after CORS (browsers can read the 429) and after Spring's observation filter (429s
   appear in `http.server.requests`), before everything else, including Spring Security later.
7. **In-memory for now** (one instance). **Running more than one instance requires** the Redis-backed
   proxy manager (`bucket4j_jdk17-lettuce`) with a short request timeout and **fail-open** (allow, log,
   alert) when Redis is unavailable: an outage of the limiter must not become an outage of the API. The
   edge (load balancer / gateway / CDN) adds a coarse per-IP limit in front, as the first line against
   volumetric floods, which an application-level limiter cannot absorb.
8. Locked in by `RateLimitTests` (5 HTTP tests), `RateLimitFilterTests` (3) and `RateLimitPropertiesTests`
   (5). Mutation checks: keying on `X-Forwarded-For`, an unbounded map, limiting every path, not exposing
   `Retry-After`, or ordering before the observation filter each fails the test that guards it.

## Consequences

**Positive**
- One client can't exhaust the API; a well-behaved client never sees a 429 (unlike the window counters).
- Clients know when to retry; browsers can read it.
- No external dependency yet; a documented path to distributed limits.

**Negative / accepted trade-offs**
- Clients behind one address (an office NAT, a carrier-grade NAT) share a bucket until requests are keyed by
  user. The default is generous for that reason.
- Per-instance limits multiply with the number of instances until the Redis step is done (decision 7).
- Under memory pressure (more than 100 000 active clients) the least recent buckets are dropped, which
  gives those clients a fresh bucket: a deliberate choice of a bounded heap over exactness.
- One policy for all endpoints. Expensive operations (login, search, writes) will need their own, stricter
  buckets.

**Follow-ups**
- With Spring Security: key authenticated requests by user id; stricter bucket for login (credential
  stuffing).
- With a second instance: Redis-backed buckets (decision 7), with a test against a real Redis
  (Testcontainers).
- `RateLimit` / `RateLimit-Policy` response headers once the IETF draft (`draft-ietf-httpapi-ratelimit-headers`)
  is final.

## References

- RFC 9110 §10.2.3 `Retry-After`; RFC 6585 §4 `429 Too Many Requests`
- Bucket4j reference documentation; Apache Tomcat `RemoteIpValve`
- Learning note: `learning/37-rate-limiting.md`
