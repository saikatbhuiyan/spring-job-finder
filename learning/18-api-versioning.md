# 18 — API Versioning

## Goal

APIs change over time: new fields, new formats, new business rules, retired features. **API
versioning** lets an API evolve **without breaking the clients that already depend on it**.

This note covers:

- the FoodRush story: what happens without versioning, and how versioning fixes it;
- breaking vs non-breaking changes;
- the four versioning strategies, each verified with **Spring Framework 7's built-in API
  versioning** (new in Spring 7 / Boot 4);
- the deprecation and sunset lifecycle (standard HTTP headers);
- the decision applied to Job Finder, with tests.

Verified on Spring Boot 4.2.0 / Spring Framework 7.1.0.

Status: ✅ **applied**. Path versioning is configured in the project and locked in by tests.

---

## 1. A real-world scenario: FoodRush

*(The scenario comes from the EazyBytes course. The incident timeline and numbers are
illustrative.)*

### The setup

FoodRush, a food delivery company, has **one API** that powers:

| Client | Scale | How it updates |
|--------|-------|----------------|
| Mobile app (iOS & Android) | 1 million users | App-store release; **many users never update** |
| Website | 500K daily visitors | Deployed by FoodRush, so it can change quickly |
| Restaurant partner apps | 5,000 restaurants | Tablets in kitchens, updated rarely |
| Delivery partner apps | 10,000 drivers | Updated when drivers get around to it |
| Third-party integrations | 50 business partners | **Other companies' code**: release cycles of weeks or months |

The original endpoint, with **no version**:

```http
GET https://api.foodrush.com/restaurants/menu/{restaurant_id}
```

```json
{
  "restaurant": "Pizza Palace",
  "items": [
    { "id": 101, "name": "Margherita Pizza", "price": 12.99, "available": true }
  ]
}
```

### Act 1: the change, without versioning

FoodRush expands to new countries and needs two things:

1. **Multi-currency prices:** `12.99` alone doesn't say *which* currency.
2. **Real stock counts:** restaurants want "only 3 left" instead of `true`/`false`.

A developer updates the endpoint **in place**:

```json
{
  "restaurant": "Pizza Palace",
  "items": [
    { "id": 101, "name": "Margherita Pizza",
      "price": { "amount": 12.99, "currency": "USD" },
      "stock": 14 }
  ]
}
```

It's tested with the newest website build, looks fine, and is deployed on **Friday at 6 PM**.

**What happens next:**

| Time | Impact |
|------|--------|
| 6:05 PM | **Mobile apps crash** on the menu screen. Older app versions expect `price` to be a **number**, receive an **object**, and fail to parse. Most of the 1M users can't order during dinner rush. |
| 6:10 PM | **Restaurant tablets** read `available`, which no longer exists, so it's treated as missing/`false`. **Every item shows "Sold out."** Kitchens stop receiving orders. |
| 6:15 PM | **Delivery apps** fail to show order contents and drivers can't confirm pickups. |
| 6:30 PM | **Partner integrations** (corporate catering, aggregators) start failing. Contracts with SLAs mean **financial penalties**. |
| 6:45 PM | Support is flooded. Social media fills with "FoodRush is down". |
| 7:30 PM | The team **rolls back**. But the new website build now breaks, because it expects the new format. Some data written in the new shape needs manual repair. |
| Monday | Post-mortem: lost orders, refunds, SLA penalties, damaged trust. And the business **still** needs multi-currency. |

**The root cause isn't the change. It's that every client was forced to switch at the same
instant.** You can't make a million phones, 5,000 tablets and 50 partner companies update at
6 PM on a Friday.

### Act 2: the same change, with versioning

FoodRush versions its API:

```http
GET https://api.foodrush.com/api/v1/restaurants/{id}/menu   ← the original contract, unchanged
GET https://api.foodrush.com/api/v2/restaurants/{id}/menu   ← the new contract
```

| When | What happens |
|------|--------------|
| Day 0 | **v2 launches next to v1.** v1 keeps returning the old format. **Nothing breaks.** |
| Day 0 | v1 responses start carrying **deprecation headers**: "v1 is deprecated, it will be switched off on 2027-05-01, here's the migration guide." (Verified in §5.) |
| Week 1 | The website moves to v2 first, since FoodRush controls it. |
| Weeks 2–8 | A new mobile release uses v2. Old installs keep working on v1. Partners get the migration guide and a sunset date. |
| Ongoing | Dashboards show **requests per version**. v1 traffic falls week by week. |
| Month 5 | The few remaining v1 callers (mostly old apps and one slow partner) get direct reminders. |
| Sunset date | v1 is switched off. The few calls left get a clear error that points to v2. |

Same business change, **zero outage**, and every client migrated on its own schedule.

---

## 2. Breaking vs non-breaking changes

**Only breaking changes need a new version.** Don't bump the version for everything.

| Change | Breaking? | Why |
|--------|-----------|-----|
| **Add** an optional response field (e.g. `calories`) | ✅ No | Well-behaved clients ignore unknown fields (Spring/Jackson does by default, note 17 §5) |
| **Add** a new endpoint | ✅ No | Old clients don't call it |
| **Add** an optional request field / query param with a default | ✅ No | Old requests still valid |
| **Remove** or **rename** a field (`available` → `stock`) | ❌ Yes | Clients reading it get nothing |
| **Change a field's type** (`price`: number → object) | ❌ Yes | Clients fail to parse |
| Make an optional request field **required** | ❌ Yes | Old requests now rejected |
| Change **meaning** (price now includes tax) | ❌ Yes | Same shape, wrong behavior. The most dangerous kind |
| Change status codes / error format | ❌ Yes | Client error handling breaks |
| Remove an endpoint | ❌ Yes | Clients get 404 |

FoodRush's change was breaking twice: a **type change** (`price`) and a **rename** (`available`).

---

## 3. The four versioning strategies (all verified with Spring 7)

Same FoodRush API in every case. v2 is the breaking change from §1.

### 3.1 URI / path versioning

```http
GET /api/v1/restaurants/7/menu
GET /api/v2/restaurants/7/menu
```

Boot configuration:

```properties
spring.mvc.apiversion.use.path-segment=1          # 0 = "api", 1 = "v1"
```

Controller:

```java
@RestController
@RequestMapping("/api/{version}/restaurants/{id}")
class MenuController {
    @GetMapping(value = "/menu", version = "1") MenuV1 menuV1(@PathVariable long id) { ... }
    @GetMapping(value = "/menu", version = "2") MenuV2 menuV2(@PathVariable long id) { ... }
}
```

Verified:

```text
GET /api/v1/restaurants/7/menu  -> 200 {"restaurant":"Pizza Palace","items":[{"id":101,"name":"Margherita Pizza","price":12.99,"available":true}]}
GET /api/v2/restaurants/7/menu  -> 200 {"restaurant":"Pizza Palace","items":[{"id":101,"name":"Margherita Pizza","price":{"amount":12.99,"currency":"USD"},"stock":14}]}
GET /api/1.0/restaurants/7/menu -> 200 (same as v1: "v1", "1" and "1.0" all parse to version 1.0.0)
GET /api/v3/restaurants/7/menu  -> 400 {"detail":"Invalid API version: '3.0.0'.","status":400,"title":"Bad Request"}
```

### 3.2 Request parameter versioning

```http
GET /api/restaurants/7/menu?version=2
```

```properties
spring.mvc.apiversion.use.query-parameter=version
```

```text
GET /api/restaurants/7/menu?version=1 -> 200 (v1 format)
GET /api/restaurants/7/menu?version=2 -> 200 (v2 format)
GET /api/restaurants/7/menu           -> 400 {"detail":"API version is required.",...}
```

### 3.3 Header versioning

```http
GET /api/restaurants/7/menu
X-API-Version: 2
```

```properties
spring.mvc.apiversion.use.header=X-API-Version
spring.mvc.apiversion.default=1                    # requests without the header get v1
```

```text
GET /api/restaurants/7/menu  [X-API-Version: 2]   -> 200 (v2 format)
GET /api/restaurants/7/menu  (no header)          -> 200 (v1 format: the default)
GET /api/restaurants/7/menu  [X-API-Version: abc] -> 400 {"detail":"Invalid API version: 'abc'.",...}
```

### 3.4 Media type versioning (content negotiation)

```http
GET /api/restaurants/7/menu
Accept: application/json;v=2
```

```properties
spring.mvc.apiversion.use.media-type-parameter[application/json]=v
```

```text
GET ...  [Accept: application/json;v=1] -> 200 (v1 format)
GET ...  [Accept: application/json;v=2] -> 200 (v2 format)
GET ...  [Accept: application/json]     -> 400 {"detail":"API version is required.",...}
```

(GitHub's API is a well-known example of this style: `Accept: application/vnd.github+json`
plus an API-version header.)

**Note:** the controller code is identical for strategies 2–4. Only the property changes. Spring 7
separates *where the version comes from* (the resolver) from *which method handles which version*
(`version = "..."`).

### 3.5 Comparison

| | Path `/api/v1` | Query `?version=1` | Header `X-API-Version` | Media type `;v=1` |
|-|----------------|--------------------|------------------------|-------------------|
| Visible / easy to try in a browser | ✅ Very | ✅ Yes | ❌ Needs tools | ❌ Needs tools |
| Easy to document and discover | ✅ | ✅ | ⚠️ | ⚠️ |
| HTTP caching (CDN, proxies) | ✅ Different URLs = different cache entries | ✅ | ⚠️ Needs `Vary: X-API-Version` | ⚠️ Needs `Vary: Accept` |
| Routing / gateways / logs | ✅ Trivial | ✅ | ⚠️ Must inspect headers | ⚠️ |
| "Pure REST" (URL = resource identity) | ❌ Same resource, several URLs | ❌ | ✅ | ✅ Most "correct" |
| Used by | Twitter/X, Stripe-style URLs, most public APIs | Some Google/AWS APIs | Many internal APIs | GitHub |

**There's no single right answer.** Path versioning is the most **pragmatic and widely used** for
public REST APIs. Header and media type are cleaner in theory but harder for clients and
infrastructure.

---

## 4. Spring 7 built-in versioning: details (verified)

Before Spring 7 you wrote this by hand: separate controllers, `params`/`headers` conditions,
custom code. Now it's built in.

| Feature | How | Verified |
|---------|-----|----------|
| Map a method to a version | `@GetMapping(version = "2")` | ✅ |
| **Baseline version: "this version and later"** | `version = "1+"` | `GET /api/v2/restaurants/7` matched a `"1+"` endpoint → `200` |
| Semantic version parsing | `SemanticApiVersionParser` (default) | `v1`, `1`, `1.0` → `1.0.0` |
| Supported versions | Auto-detected from `version` attributes (`detect-supported=true`), or `spring.mvc.apiversion.supported=1,2` | `v3` → `400 Invalid API version` |
| Required version | Default when no `default` is set | Missing → `400 API version is required.` |
| Default version | `spring.mvc.apiversion.default=1` | No header → v1 |
| Errors | `InvalidApiVersionException` / `MissingApiVersionException` → **400 Problem Details** | ✅ |
| Deprecation headers | `StandardApiVersionDeprecationHandler` | ✅ §5 |

**`"1+"` is what keeps versioning cheap.** Endpoints that didn't change in v2 are declared once
with `version = "1+"` and serve every version from 1 upwards. Only the endpoints that actually
changed get separate v1/v2 methods.

---

## 5. Deprecation and sunset: telling clients to move (verified)

Launching v2 isn't the end. You have to **get clients off v1** and eventually **turn it off**.
Spring 7 sends the standard headers automatically:

```java
@Bean
WebMvcConfigurer apiDeprecation() {
    return new WebMvcConfigurer() {
        @Override
        public void configureApiVersioning(ApiVersionConfigurer configurer) {
            var handler = new StandardApiVersionDeprecationHandler();
            handler.configureVersion("1")
                   .setDeprecationDate(ZonedDateTime.parse("2026-11-01T00:00:00Z"))
                   .setSunsetDate(ZonedDateTime.parse("2027-05-01T00:00:00Z"))
                   .setDeprecationLink(URI.create("https://api.foodrush.com/docs/migrate-v1-to-v2"));
            configurer.setDeprecationHandler(handler);
        }
    };
}
```

Every v1 response then carries (verified, in all four strategies):

```http
Deprecation: @1793491200
Sunset: Sat, 01 May 2027 00:00:00 GMT
Link: <https://api.foodrush.com/docs/migrate-v1-to-v2>; rel="deprecation"; type="text/html"
```

| Header | Standard | Meaning |
|--------|----------|---------|
| `Deprecation` | RFC 9745 | "This is deprecated since…" (Unix timestamp after `@`) |
| `Sunset` | RFC 8594 | "This stops working on…" |
| `Link; rel="deprecation"` | RFC 9745 | Where to read the migration guide |

v2 responses have **none** of these headers (verified).

This works alongside the Boot properties: Boot configures the strategy, and our `WebMvcConfigurer`
adds the deprecation handler on top of it.

### The version lifecycle

```text
 Active ──▶ Deprecated ──────────────▶ Sunset ──▶ Removed
   │          │ headers on every         │ date      │ clear error pointing to v2
   │          │ response, docs,          │ reached   │ (e.g. 410 Gone or 400 + guide)
   │          │ emails to partners,      │
   │          │ track usage per version  │
```

**Production practices:**

1. **Announce early:** typical deprecation windows are **6–12 months** for public APIs.
2. **Measure:** log or record a metric of requests per version (🔜 with Micrometer), and don't sunset
   while important clients still call v1.
3. **Support N and N−1:** keep at most two major versions alive. Each live version costs testing
   and maintenance.
4. **Keep a changelog and a migration guide** for every major version.

---

## 6. ✅ Applied to Job Finder

### Decision

**Path versioning, `/api/v1/...`, using Spring 7's built-in support.**

Why:
- Job Finder is a **public-style REST API** (web frontend, possibly mobile and partners), where
  visibility, documentation, caching and gateway routing matter most.
- It matches the `/api/v1` convention already chosen in note 16 §8.
- It's the most common choice, so it's instantly familiar to reviewers and API consumers.

Configured in [application.properties](../src/main/resources/application.properties):

```properties
spring.mvc.apiversion.use.path-segment=1
```

Future controllers follow this pattern:

```java
@RestController
@RequestMapping("/api/{version}/jobs")
class JobController {
    @GetMapping(version = "1+")      // unchanged endpoints serve every version
    List<JobResponse> search(...) { ... }
}
```

### Locked in by tests

[ApiVersioningTests.java](../src/test/java/com/shahabuddin/jobfinder/ApiVersioningTests.java)
adds a **test-only** versioned controller (production has no business endpoints yet) and checks the
contract:

```text
GET /api/v1/versioning-probe  -> 200 "v1"                                       resolvesVersionFromPath
GET /api/v99/versioning-probe -> 400 application/problem+json
                                 "Invalid API version: '99.0.0'."               rejectsUnsupportedVersionWithProblemDetail
GET /actuator/health          -> 200 UP                                         actuatorIsNotAffectedByVersioning
```

```text
Tests run: 3 (ApiVersioningTests) + 1 (ErrorResponseTests) + 5 (HealthEndpointTests), Failures: 0
```

**Why the Actuator test matters:** "version = path segment 1" could, in principle, read
`/actuator/health` as version `"health"` and break the health checks that Docker/Kubernetes depend
on. The test proves it doesn't (Actuator endpoints are routed by their own handler mapping, separate
from our controllers), and it will catch a regression if that ever changes.

---

## 7. Best practices summary

1. **Version from day one.** Adding a version later is itself a breaking change (FoodRush's problem).
2. **Bump the major version only for breaking changes** (§2). Add fields freely.
3. **Clients should be tolerant readers:** ignore unknown fields (Spring does by default).
4. **Reuse unchanged endpoints** across versions (`version = "1+"`).
5. **Deprecate loudly** (headers, docs, emails) with a **sunset date**, and measure usage before removing.
6. **Support at most N and N−1.**
7. **Map versions to DTOs, not to entities.** `MenuV1`/`MenuV2` records are mapped from the same
   domain model; the service layer is shared.
8. **Test the contract** for each live version, including errors.

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| No versioning until the first breaking change | You have to break clients to introduce versioning (FoodRush) |
| New version for every small additive change | Version explosion, costly maintenance |
| Copy-pasting the whole controller and service per version | Duplicated business logic drifts apart; version the DTO/mapping layer instead |
| Breaking change "hidden" in the same version (meaning changes, same shape) | Silent wrong behavior, the hardest bugs |
| Removing v1 without deprecation headers, notice or usage data | Same outage as no versioning, just later |
| Header/media versioning without `Vary` | CDNs/proxies serve the wrong version from cache |

## Interview questions

**Q: Why version an API?**
To make breaking changes without breaking existing clients: old and new contracts run side by side
while clients migrate.

**Q: What are the main versioning strategies?**
URI path (`/v1/`), request parameter (`?version=1`), custom header (`X-API-Version`), and media type /
content negotiation (`Accept: application/json;v=1`).

**Q: Which would you choose and why?**
For a public REST API, usually URI path versioning: visible, easy to document, cache-friendly, and
simple to route. Header/media type are cleaner REST but harder for clients and infrastructure.

**Q: What counts as a breaking change?**
Removing or renaming fields, changing types, adding required inputs, changing semantics, status codes,
or error formats. Adding optional fields or endpoints is not breaking.

**Q: How does Spring Framework 7 support versioning?**
Built-in: configure a resolver (path segment, header, query param, media type parameter) via
`ApiVersionConfigurer` or `spring.mvc.apiversion.*`, and map methods with `@GetMapping(version = "2")`.
`"1+"` declares a baseline version, and a deprecation handler adds `Deprecation`/`Sunset` headers.

**Q: How do you retire an old version?**
Deprecate it (RFC 9745 `Deprecation` + RFC 8594 `Sunset` headers, docs, notices), monitor usage per
version, then remove it after the sunset date with a clear error pointing to the new version.
