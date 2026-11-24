# 37 — Rate Limiting in Spring Boot REST APIs: Every Algorithm, Measured

## Goal

**Rate limiting** caps how many requests one client may send in a period: "60 requests per minute per
client". It protects the API and its database from being overwhelmed by a single client (a runaway
script, a scraper, a credential-stuffing bot) and keeps the service fair for everyone else.

This note:

1. explains **every common algorithm** with a picture, working Java code, and its pros and cons;
2. runs all of them against the **same traffic** on a simulated clock, so their differences are numbers,
   not opinions;
3. shows the **production problems** that have nothing to do with the algorithm (concurrency, several
   instances, client identity, memory, what happens when Redis is down), each measured;
4. ends with **what is actually used, and what's safe**, and how Job Finder does it.

Status: ✅ **applied** ([ADR 0012](../docs/adr/0012-rate-limiting.md)): Bucket4j token bucket per client.
Experiments: `Algorithms.java` (plain Java), Bucket4j 8.22.0 with Redis 7 in Docker, Job Finder tests.

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **limit** / **rate** | how many requests per period: 10 per 60 s means one every 6 s *on average* |
| **burst** | requests arriving faster than the rate for a short time (e.g. a page loading 10 resources at once) |
| **key** | who is limited: an IP address, a user, an API key, a user + endpoint |
| **window** | the period requests are counted in |
| **429** | `Too Many Requests` (RFC 6585), the status for "over the limit" |
| **`Retry-After`** | header (RFC 9110): seconds until the client may try again |

The experiment uses one policy for every algorithm: **10 requests per 60 seconds**, burst capacity 10
where the algorithm has one. Four traffic patterns:

| Pattern | Requests | What it tests |
|---|---|---|
| **A** boundary burst | 10 at 59.0–59.9 s, 10 at 60.0–60.9 s | a burst around a window edge |
| **B** sustained overload | 1 per second for 10 minutes (600) | a client that never stops |
| **C** burst after idle | idle 10 min, then 30 at once | the size of a burst |
| **D** compliant | exactly 1 per 6 s for 10 minutes (100) | does a well-behaved client get through? |

For each run: requests **allowed**, and the **worst 60 s**: the most requests allowed inside any 60-second
span. That's the rate a client really achieved, and shows whether the limit holds.

---

## 2. Fixed window counter

```text
 window 0 (0–60 s)            window 1 (60–120 s)
 [ count: 0 1 2 ... 10 ✗ ]    [ count: 0 1 2 ... ]       counter resets at every window edge
```

Count requests per calendar window; reset when a new window starts.

```java
long window = -1; int count;
boolean tryAcquire(long now) {
    long w = now / WINDOW;                       // which window are we in?
    if (w != window) { window = w; count = 0; }  // new window: reset
    if (count < LIMIT) { count++; return true; }
    return false;
}
```

**Measured:** pattern A, **20 allowed in under 2 seconds**, twice the limit. The first 10 fill window 0
just before 60 s, the next 10 fill window 1 just after.

| Pros | Cons |
|---|---|
| simplest; 2 numbers per key | **up to 2× the limit at window edges** (measured) |
| trivial in Redis: `INCR key` + `EXPIRE` | everyone's counter resets at the same moment: traffic spikes at the top of each minute |
| easy to explain ("100 per hour, resets at :00") | |

## 3. Sliding window log

```text
 timestamps of accepted requests in the last 60 s:   [12.0, 15.3, 31.8, ... ]
 new request at t: drop everything older than t-60s, allow if fewer than 10 remain
```

```java
ArrayDeque<Long> log = new ArrayDeque<>();
boolean tryAcquire(long now) {
    while (!log.isEmpty() && log.peekFirst() <= now - WINDOW) log.pollFirst();
    if (log.size() < LIMIT) { log.addLast(now); return true; }
    return false;
}
```

**Measured:** exact everywhere: 10 in pattern A, worst 60 s always ≤ 10, all compliant requests allowed.

| Pros | Cons |
|---|---|
| exact: never more than the limit in any window | memory **grows with the limit**: 1000/hour = up to 1000 timestamps *per client* |
| no edge effects | more work per request (evicting old entries); in Redis a sorted set per key |

## 4. Sliding window counter

```text
 previous window: 10 requests    current window: 3 requests, we're 40% into it
 estimate = 10 × (1 − 0.4) + 3 = 9   → allowed (9 < 10)
```

Keep only two counters and assume the previous window's requests were spread evenly.

```java
long window = -1; int current, previous;
boolean tryAcquire(long now) {
    long w = now / WINDOW;
    if (w != window) { previous = (w == window + 1) ? current : 0; current = 0; window = w; }
    double elapsed = (now % WINDOW) / (double) WINDOW;
    double estimate = previous * (1 - elapsed) + current;
    if (estimate < LIMIT) { current++; return true; }
    return false;
}
```

**Measured:** pattern A, 11 (close to exact). But pattern D, the **compliant** client: **95 of 100
allowed**. At a steady rate exactly at the limit, the estimate sits right at 10, and rounding pushes it
over: the approximation **rejects well-behaved clients**.

| Pros | Cons |
|---|---|
| 3 numbers per key; much smoother than a fixed window | an **approximation**: can let a few too many through, and can reject compliant clients (measured) |
| cheap in Redis (two counters) | slightly harder to explain to API users |

## 5. Token bucket

```text
           refill: 1 token every 6 s
                 │
           ┌─────▼─────┐
           │ ● ● ● ● ● │  capacity 10 tokens
           └─────┬─────┘
                 │  each request takes 1 token; no token → 429
```

The bucket starts full. Tokens drip in at the rate, up to the capacity. A request takes a token.

```java
double tokens = CAPACITY; long last = 0;
boolean tryAcquire(long now) {
    tokens = Math.min(CAPACITY, tokens + (now - last) * REFILL_PER_MS);  // refill since last time
    last = now;
    if (tokens >= 1) { tokens -= 1; return true; }
    return false;
}
```

No timer is needed: the refill is calculated from elapsed time when a request arrives.

**Measured:** A: 10. C: 10 (the burst size is the capacity). D: 100/100. But **B: worst 60 s = 19**. A
client sending continuously gets the full bucket (10) *plus* the refill during that minute (~9). That's
the token bucket's contract: **burst + rate**, "at most `capacity + rate × t` in any period `t`", not
"at most 10 in any 60 s". Choose the capacity with that in mind.

| Pros | Cons |
|---|---|
| allows natural bursts, then enforces the average | the burst means up to capacity + rate in one period (measured 19 for "10/min") |
| 2 numbers per key; O(1) | two knobs (capacity, refill rate) to understand |
| answers "when is the next token?" exactly → `Retry-After` | |
| the most widely used algorithm; libraries everywhere (Bucket4j, Guava's `RateLimiter` is similar) | |

## 6. Leaky bucket

Two different things share this name.

**As a queue (traffic shaping):** requests enter a queue and leave at a constant rate; a full queue
rejects.

```text
 requests ──▶ [ queue of 10 ] ──▶ leaks out 1 every 6 s ──▶ controller
```

```java
long nextFree = 0;
boolean tryAcquire(long now) {
    long start = Math.max(now, nextFree);
    if ((start - now) / INTERVAL >= LIMIT) return false;   // queue full
    nextFree = start + INTERVAL;                            // this request runs at 'start'
    return true;
}
```

**Measured:** output is perfectly smooth, but accepted requests were **delayed by up to 59 seconds**.
An HTTP client would have timed out long before; the server holds a thread or connection for each queued
request. Good for smoothing *outgoing* calls (to a payment provider with a strict rate); bad for incoming
API requests.

**As a meter:** a "water level" that drains at the rate; a request adds 1 if it doesn't overflow. That's
the token bucket upside down: the same decisions, the same pros and cons. (NGINX's `limit_req` is
described in its documentation as a leaky bucket; with `nodelay` it rejects instead of queueing.)

| Pros | Cons |
|---|---|
| (queue) perfectly constant output rate | (queue) **adds latency** (measured up to 59 s), holds resources while waiting |
| (meter) same as token bucket | no bursts at all in queue form |

## 7. GCRA (generic cell rate algorithm)

```text
 emission interval T = 6 s, burst tolerance τ = 9 × T = 54 s
 state: one timestamp, TAT ("theoretical arrival time" of the next request)
 allow if  now ≥ TAT − τ ;  then  TAT = max(TAT, now) + T
```

From ATM networking: instead of counting tokens, remember *when* the client would next be "on schedule".

```java
long tat = 0;
boolean tryAcquire(long now) {
    long newTat = Math.max(tat, now) + EMISSION;
    if (newTat - TOLERANCE > now + EMISSION) return false;
    tat = newTat;
    return true;
}
```

**Measured:** identical decisions to the token bucket in all four patterns, with **one number** per key.

| Pros | Cons |
|---|---|
| token-bucket behaviour with a single timestamp | less intuitive to read and explain |
| no floating point; easy to do atomically (one value to compare-and-set) | fewer ready-made libraries in Java |
| `Retry-After` falls out directly (`TAT − τ − now`) | |

## 8. Not a rate limiter: concurrency limiting

A **semaphore** caps how many requests are *in progress*, regardless of time.

```java
Semaphore inFlight = new Semaphore(2);
if (!inFlight.tryAcquire()) return 429-or-503;
try { handle(); } finally { inFlight.release(); }
```

**Measured:** 5 simultaneous slow requests → 2 accepted, 3 rejected. It protects against *slow*
requests piling up (a slow report endpoint), which a requests-per-minute limit can't see. Use it next to a
rate limiter for expensive endpoints (a "bulkhead").

## 9. All results side by side

| Algorithm | A: boundary burst | B: sustained, worst 60 s | C: burst after idle | D: compliant | State per key |
|---|---|---|---|---|---|
| Fixed window | **20** | 10 | 10 | 100/100 | 2 numbers |
| Sliding log | 10 | 10 | 10 | 100/100 | ≤ 10 timestamps |
| Sliding window counter | 11 | 10 | 10 | **95/100** | 3 numbers |
| Token bucket | 10 | 19 | 10 | 100/100 | 2 numbers |
| Leaky bucket (queue) | 11 (delay ≤ 59 s) | 20 | 11 | 100/100 | 1 number + the queue |
| GCRA | 10 | 19 | 10 | 100/100 | 1 number |

---

## 10. Production problem 1: concurrency

The algorithm is only correct if "read the counter, compare, write" happens **atomically**. Measured with
16 threads × 1000 attempts against a fixed window with limit 100:

```text
naive (read, compare, write):   allowed 564, 1014, 748   (three runs)
atomic (compare-and-set loop):  allowed 100
```

Up to **10× the limit**. A `synchronized` method, an `AtomicLong` compare-and-set loop, or a library
that does this (Bucket4j is lock-free) is required. In Redis, the same rule: one Lua script or a
compare-and-swap, never `GET` then `SET`.

## 11. Production problem 2: more than one instance

Behind a load balancer with two instances, each holding its own buckets in memory (measured, Bucket4j,
40 requests round-robin, limit 10):

```text
2 instances, in-memory buckets      → allowed 20   (limit × instances)
2 instances, one bucket in Redis    → allowed 10
16 threads on 2 instances, Redis    → allowed 10   (atomic under contention)
```

Bucket4j's Redis integration (`bucket4j_jdk17-lettuce`) stores the bucket state in Redis and updates it
with compare-and-swap. Keys expire by themselves (measured `PTTL` ≈ 67 s for an emptied bucket: time to
refill, plus the configured 10 s), so Redis memory doesn't grow forever.

**The cost**, measured on local Docker: **0.4 µs** per check in memory, **~454 µs** via Redis, about
1000× more and one network round trip per request.

**When Redis is down**, measured: every check threw `io.github.bucket4j.TimeoutException` after the full
timeout (1 s). Without a decision, a Redis outage becomes an API outage (every request waits a second,
then fails). Production choices:

| Policy | Effect | Use when |
|---|---|---|
| **fail open** (allow, log, alert) | the API stays up without limits for a while | the limiter is protection, not billing (most APIs) |
| fail closed (reject) | the API is down while Redis is | limits are contractual (paid quotas) |
| local fallback | in-memory buckets while Redis is down | best of both, more code |

Always set a **short timeout** (tens of milliseconds, not Lettuce's documented default of 60 s).

## 12. Production problem 3: who is the client?

The obvious key is the client's IP address. Behind a reverse proxy or load balancer, every request comes
from the **proxy's** address, so all users share one bucket. The proxy adds the real address in
`X-Forwarded-For`. But a client can send that header itself.

Measured in Job Finder, a client rotating a forged `X-Forwarded-For` (limit 3):

| Server configuration | 4th request with a new forged address |
|---|---|
| default (forwarded headers not used) | **429**: header ignored, limit holds |
| `server.forward-headers-strategy=framework` | **200**: header trusted from anyone, every forged value is a new client |
| `native` with Tomcat's default trusted proxies | **200**: the defaults trust loopback and private networks |
| `native` + `server.tomcat.remoteip.internal-proxies=<the proxy only>` | **429**: forged header from an untrusted peer ignored |

And with the proxy trusted correctly, a client-supplied first entry doesn't win: Tomcat's `RemoteIpValve`
takes the **rightmost address that isn't a trusted proxy** (measured: `203.0.113.9, 198.51.100.7` →
`198.51.100.7`).

Rules:

1. Never read `X-Forwarded-For` yourself.
2. Behind a proxy: `forward-headers-strategy=native` **and** `internal-proxies` = the proxy's addresses
   only; the proxy must *append* the client address.
3. Once users log in, key by **user id** (and API key for machine clients): addresses are shared (office
   NAT, mobile carriers) and cheap to change (IPv6).

## 13. Production problem 4: memory

One bucket per client in a `HashMap` grows forever. With IPv6, an attacker can use a different source
address for every request. Measured: 5000 rotating addresses against a bounded Caffeine cache of 100 →
at most 100 buckets kept. A bucket untouched for one refill period is full again, so dropping it after
that time changes nothing (`expireAfterAccess(period)`).

## 14. Production problem 5: what the client sees

```http
HTTP/1.1 429
Retry-After: 20
Content-Type: application/problem+json

{"title":"Too Many Requests","status":429,"detail":"Too many requests. Retry after 20 seconds.","instance":"/api/v1/jobs"}
```

- `429`, not `503` (that means the *server* is overloaded) and not `403`.
- `Retry-After` in seconds, so clients can back off instead of retrying in a loop.
- **Browsers**: the 429 needs CORS headers, and `Retry-After` must be an *exposed* header, or JavaScript
  can't read it (tested).
- Many APIs also send the remaining quota (`X-RateLimit-Limit`, `-Remaining`, `-Reset`, a de-facto
  convention). An IETF draft standardizes `RateLimit-Policy` / `RateLimit`; until it's final, Job Finder
  sends only the standard `Retry-After`.

## 15. Production problem 6: where the limiter sits

```text
 Internet ─▶ CDN / load balancer / API gateway ─▶ [ CorsFilter ─▶ observation ─▶ RateLimitFilter ─▶ (Security) ─▶ MVC ]
             coarse per-IP limits, DDoS            the application: per-client, per-user, per-endpoint
```

- **At the edge** (Cloudflare, AWS WAF, NGINX, a gateway): absorbs floods before they reach the app. An
  application can't defend itself against a volumetric attack; by the time a request reaches Java, the
  connection and the parsing are already paid for.
- **In the application**: knows the user, the endpoint, the cost of an operation. Both, not either.
- Inside the app, the order matters (measured): **after CORS** (browsers can read the 429), **after the
  observation filter** (ordered before it, rejected requests didn't show up in metrics at all), **before
  security** (a flood of bad logins shouldn't cost a password hash each).
- **Never limit health probes**: Kubernetes would see a failing liveness probe and restart a healthy pod.

## 16. What is actually used, and what's safe

From their public documentation (not verified here): AWS API Gateway and Envoy's local rate limiter use
**token buckets**; NGINX's `limit_req` describes itself as a **leaky bucket**; Cloudflare has described a
**sliding window counter**; Stripe has written about **token buckets**; GitHub's REST API limits per
**fixed hourly window** (with `X-RateLimit-Reset`); `redis-cell` implements **GCRA**.

**The safe default for an API: a token bucket (or GCRA) per client**:

- allows the bursts real clients produce, then enforces the average;
- never rejects a compliant client (unlike the window counters, measured);
- tiny state, O(1), an exact `Retry-After`;
- mature, lock-free libraries exist (Bucket4j), with Redis backends.

**Safe** also means the parts around it: atomic updates; shared state (Redis) once there's more than one
instance, with a short timeout and fail-open; a client identity that can't be forged; bounded memory; a
`429` with `Retry-After` that browsers can read; health probes excluded; an edge limit in front.

## 17. Job Finder's implementation

```java
final class RateLimitFilter extends OncePerRequestFilter {           // registered for /api/*
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(properties.maxClients())                    // bounded memory
            .expireAfterAccess(properties.period())                  // idle bucket = full bucket
            .build();

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String client = request.getRemoteAddr();                     // never a client-controlled header
        ConsumptionProbe probe = buckets.get(client, key -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) { chain.doFilter(request, response); return; }
        long retryAfter = Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill()));
        reject(request, response, retryAfter);                       // 429 + Retry-After + Problem Details
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(60).refillGreedy(60, Duration.ofMinutes(1)))
                .build();
    }
}
```

- `refillGreedy` adds tokens continuously (one per second here); `refillIntervally`, per Bucket4j's
  documentation, adds all 60 at once every minute: a fixed window in disguise.
- The rejection is written by the filter itself, without dispatching to Spring MVC: rejecting must be
  cheap, since it happens most when the app is under pressure.
- Configuration: `jobfinder.rate-limit.requests`, `.period`, `.max-clients`, `.enabled`, validated at
  startup (`requests=0` or a period under a second fails the start).
- Tests use their own limits: all test requests come from 127.0.0.1, so a test-only
  `src/test/resources/config/application.properties` raises the limit for the rest of the suite (Spring
  Boot reads `classpath:/config/` after `classpath:/`, key by key).
- Library version: 8.22.0, not the 8.23.0 released two days earlier. A new release gets a few weeks in the
  wild first.

## 18. Verification summary

| Check | Result |
|-------|--------|
| `Algorithms.java`: 6 algorithms × 4 traffic patterns on a simulated clock | table §9 |
| Concurrency: naive vs compare-and-set, 16 threads | 564–1014 vs exactly 100 |
| Bucket4j + Redis 7: 2 instances local vs shared, contention, latency, key expiry, Redis down | 20 vs 10; 10; 0.4 µs vs 454 µs; PTTL ≈ 67 s; `TimeoutException` after 1 s |
| `X-Forwarded-For` under 3 server configurations + `RemoteIpValve` chain | table §12 |
| `RateLimitTests` (5 HTTP), `RateLimitFilterTests` (3), `RateLimitPropertiesTests` (5) | ✅ |
| Mutations: key from `X-Forwarded-For`, unbounded map, every path limited, `Retry-After` not exposed, before the observation filter | each fails the test that guards it |
| Full suite `mvn verify` | **150 tests, 0 failures** (was 137) |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Fixed window for strict limits | 2× the limit at window edges (measured) |
| Non-atomic counter | up to 10× the limit under concurrency (measured) |
| In-memory limits with several instances | limit × instances (measured) |
| Keying on `X-Forwarded-For` / trusting all proxies | the limit is bypassed by forging the header (measured) |
| Unbounded map of buckets | memory grows with every new address |
| Rate limiting health probes | healthy instances get restarted |
| No timeout / fail-closed on the Redis limiter | a Redis outage becomes an API outage (measured: 1 s per request, then an error) |
| 429 without `Retry-After` | clients retry in tight loops |
| 429 without CORS headers or `Retry-After` not exposed | browsers report a "CORS error" instead |
| Leaky bucket as a queue for API requests | requests wait up to the queue length × interval (59 s measured) |
| Only an application limiter | volumetric floods still saturate the server; add an edge limit |

## Interview questions

**Q: Compare fixed window, sliding window and token bucket.**
Fixed window: simplest, but allows up to twice the limit around window edges. Sliding log: exact, memory
grows with the limit. Sliding window counter: cheap approximation, can be slightly off both ways. Token
bucket: allows bursts up to the capacity, then the average rate; tiny state; the usual choice.

**Q: Token bucket vs leaky bucket?**
A leaky bucket as a meter makes the same decisions as a token bucket. As a queue, it smooths output to a
constant rate by delaying requests, which suits outgoing calls, not incoming API requests.

**Q: How do you rate limit across several instances?**
Shared state in Redis (or another store), updated atomically (Lua script or compare-and-swap; Bucket4j
does this), with key expiry, a short timeout, and a fail-open/closed decision for outages. Plus an edge
limit at the load balancer or gateway.

**Q: How do you identify clients behind a proxy?**
Trust `X-Forwarded-For` only from your own proxies (Tomcat `RemoteIpValve` with `internal-proxies`), take
the rightmost untrusted address, and prefer user ids or API keys once requests are authenticated.

**Q: What should a rate-limited response look like?**
`429 Too Many Requests`, `Retry-After` in seconds, a machine-readable body (Problem Details); optionally the
remaining quota in headers.

**Q: What's GCRA?**
The generic cell rate algorithm: token-bucket behaviour stored as one timestamp (the theoretical arrival
time); allow if `now ≥ TAT − τ`, then `TAT = max(TAT, now) + T`. Popular in Redis-based limiters because a
single value is easy to update atomically.
