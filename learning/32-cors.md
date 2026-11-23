# 32 — CORS (Cross-Origin Resource Sharing)

## Goal

When Job Finder gets a browser frontend on another domain, the browser will block it from reading API
responses unless the API explicitly allows it. That mechanism is **CORS**. This note explains the
same-origin policy, the two kinds of CORS requests (simple and preflight), every CORS header, how Spring
implements it, the production traps (each one reproduced), and Job Finder's policy.

Status: ✅ **applied** ([ADR 0007](../docs/adr/0007-cors-policy.md)). Verified in a scratch app (both
Spring approaches side by side) and in the project: `CorsTests` (9 HTTP tests), `CorsPropertiesTests`
(11 startup checks), and the packaged jar in `prod`. Boot 4.2.0, Spring Framework 7.1.0.

---

## 1. The problem: the same-origin policy

An **origin** is `scheme + host + port`:

| URL | Origin | Same origin as `https://app.example.com`? |
|-----|--------|-------------------------------------------|
| `https://app.example.com/jobs` | `https://app.example.com` | ✅ (the path doesn't matter) |
| `http://app.example.com` | `http://app.example.com` | ❌ different scheme |
| `https://api.example.com` | `https://api.example.com` | ❌ different host (subdomains count) |
| `https://app.example.com:8443` | `https://app.example.com:8443` | ❌ different port |

Browsers enforce the **same-origin policy**: JavaScript on `https://app.example.com` may *send* a request to
`https://api.example.com`, but it **may not read the response** unless the API says so. Without this, any
website you visit could read your webmail or bank data using your logged-in session.

**CORS** is how a server relaxes that rule *for specific origins*:

```text
 Browser (page from https://app.example.com)            API (https://api.example.com)
   fetch("https://api.example.com/api/v1/jobs")
   ── GET /api/v1/jobs ──────────────────────────────▶
      Origin: https://app.example.com                     "is this origin allowed?"
   ◀─────────────────────────────────────────────────
      Access-Control-Allow-Origin: https://app.example.com    → browser lets JavaScript read it
```

**Key point: CORS is enforced by the browser.** `curl`, Postman and other servers ignore it (verified:
a request without `Origin` got `200` and the data). **CORS protects users' browsers, not your API.** Data is
protected by authentication.

---

## 2. Simple requests vs preflight requests

### Simple request: sent directly

A `GET`/`HEAD`/`POST` with only "CORS-safelisted" headers (`Accept`, `Accept-Language`, `Content-Language`,
and `Content-Type` limited to `text/plain`, `multipart/form-data` or `application/x-www-form-urlencoded`).
The browser sends it immediately with an `Origin` header and checks the response headers afterwards.

### Preflight: ask first

Anything else (`PUT`, `PATCH`, `DELETE`, `Content-Type: application/json`, `Authorization` header…) first
triggers an **`OPTIONS` preflight**:

```text
OPTIONS /api/v1/jobs
Origin: https://app.example.com
Access-Control-Request-Method: POST
Access-Control-Request-Headers: content-type, authorization

200
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE
Access-Control-Allow-Headers: content-type, authorization
Access-Control-Max-Age: 3600                       ← browser caches this answer for 1 hour
```

Only if the preflight succeeds does the browser send the real `POST`. A JSON API therefore gets preflights
for almost every write, which is why `Max-Age` matters (Chrome caps it at 2 hours, Firefox at 24).

### The headers

| Header | Direction | Meaning |
|--------|-----------|---------|
| `Origin` | request | The page's origin (set by the browser; JavaScript can't fake it) |
| `Access-Control-Request-Method` / `-Headers` | preflight request | What the real request will use |
| `Access-Control-Allow-Origin` | response | The allowed origin (or `*`) |
| `Access-Control-Allow-Methods` / `-Headers` | preflight response | What is allowed |
| `Access-Control-Max-Age` | preflight response | Seconds to cache the preflight |
| `Access-Control-Expose-Headers` | response | Response headers JavaScript may read (others are hidden) |
| `Access-Control-Allow-Credentials` | response | Whether cookies / HTTP auth may be sent |
| `Vary: Origin` | response | Tells caches the response depends on `Origin` (Spring always adds it, verified) |

---

## 3. Two ways to configure CORS in Spring

```java
// A) Spring MVC: CorsRegistry (or @CrossOrigin on a controller)
@Configuration
class MvcCors implements WebMvcConfigurer {
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("https://app.example.com").allowedMethods("GET", "POST");
    }
}

// B) Servlet filter: CorsFilter + CorsConfigurationSource
@Bean CorsConfigurationSource corsConfigurationSource() { … }
@Bean FilterRegistrationBean<CorsFilter> corsFilter(CorsConfigurationSource source) { … }
```

`@CrossOrigin` on individual controllers is variant A spread across the code base. Avoid it: the policy
should be in one place.

### Side-by-side comparison (verified, same requests against both)

| Request (with `Origin: https://app.example.com`) | A) `CorsRegistry` | B) `CorsFilter` |
|---------------------------------------------------|-------------------|-----------------|
| GET, allowed origin | `200` + `Allow-Origin` | `200` + `Allow-Origin` |
| GET, `Origin: https://evil.example` | `403 Invalid CORS request` | `403 Invalid CORS request` |
| Preflight `POST` + `content-type` | `200` ✅ | **`403`** ⚠️ (trap 1, §4) |
| Preflight `DELETE` (not allowed) | `403` | `403` |
| Controller throws → `500` | `Allow-Origin` ✅ | `Allow-Origin` ✅ |
| Unknown path → `404` | `Allow-Origin` ✅ | `Allow-Origin` ✅ |
| **Filter `sendError(401)`** (= Spring Security) | **no CORS headers** ❌ | `Allow-Origin` ✅ |

The last row decides it. MVC-level CORS runs inside the `DispatcherServlet`, so a response produced by a
**filter**, such as Spring Security's 401 for a missing token, reaches the browser **without** CORS headers.
The browser hides it, and the frontend sees an opaque "CORS error" instead of 401. It can't redirect to login
and can't read the Problem Details. **Job Finder uses B.** In the project, swapping B for A made exactly
`rejectionsByAFilterCarryCorsHeaders` fail (mutation check).

---

## 4. Traps (all verified)

### Trap 1: `new CorsConfiguration()` allows no request headers

The filter variant rejected the JSON preflight that the registry variant allowed. `CorsRegistry` silently
applies "permit defaults" (all headers, GET/HEAD/POST, 30-minute max age). A **bare `CorsConfiguration` has
none**, so `Access-Control-Request-Headers: content-type` → `403`. Always set `setAllowedHeaders(…)`. In the
project, removing it failed `preflightFromTheFrontendIsAllowedAndCached` with `403`.

### Trap 2: `*` + credentials = a 500 on every request, at runtime

```java
config.setAllowedOrigins(List.of("*"));
config.setAllowCredentials(true);
```

The app **started fine**, then every cross-origin request returned **500**:

```text
IllegalArgumentException: When allowCredentials is true, allowedOrigins cannot contain the special value "*" …
```

The CORS spec forbids `*` with credentials, and Spring only checks when a request comes in. Job Finder
validates its configuration **at startup** instead (§5).

### Trap 3: hidden response headers

Without `Access-Control-Expose-Headers`, JavaScript can read only a few "safelisted" response headers. A
frontend creating a job couldn't read the `Location` of the `201`, nor the `Deprecation`/`Sunset` warnings of
API versioning (ADR 0002). Job Finder exposes `Location, Deprecation, Sunset, Link` (tested, and the mutation
check failed without it).

### Trap 4: "the browser blocks it" doesn't mean "it didn't run"

A *simple* cross-origin `POST` (e.g. a form post) is **sent without a preflight**. If the server just
omitted CORS headers, the request would still execute, and only reading the response would be blocked.
Spring **rejects** requests from origins that aren't allowed with `403` *before* the handler runs. The test
counts handler calls: `0` for `https://evil.example`.

### Trap 5: trailing slash and paths

`https://app.example.com/` (trailing slash) matched (Spring ignores it). But `https://app.example.com/frontend`
is **not an origin** and would never match. Job Finder's startup validation rejects it.

---

## 5. Job Finder's CORS policy

```properties
# application.properties: secure default, nobody
jobfinder.cors.allowed-origins=
# application-dev.properties: local frontend dev servers
jobfinder.cors.allowed-origins=http://localhost:5173,http://localhost:3000
# production: environment variable
JOBFINDER_CORS_ALLOWEDORIGINS=https://app.example.com
```

```java
@ConfigurationProperties("jobfinder.cors")
record CorsProperties(@DefaultValue List<String> allowedOrigins, @DefaultValue("1h") Duration maxAge) {
    CorsProperties { allowedOrigins.forEach(CorsProperties::requireOrigin); … }   // fail fast
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
class CorsConfig {
    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final List<String> ALLOWED_HEADERS = List.of("Content-Type", "Authorization");
    static final List<String> EXPOSED_HEADERS = List.of("Location", "Deprecation", "Sunset", "Link");

    @Bean CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {   // name used by Spring Security
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(ALLOWED_METHODS);
        api.setAllowedHeaders(ALLOWED_HEADERS);
        api.setExposedHeaders(EXPOSED_HEADERS);
        api.setAllowCredentials(false);                // bearer tokens, not cookies
        api.setMaxAge(properties.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);   // not /actuator/**
        return source;
    }

    @Bean FilterRegistrationBean<CorsFilter> corsFilter(CorsConfigurationSource source) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);  // before every other filter (and Security later)
        return registration;
    }
}
```

| Decision | Why |
|----------|-----|
| Allowlist of **exact** origins, default **empty** | Secure by default: a new environment isn't open to every website |
| Origins per environment (property / env var); methods and headers in code | Origins differ per deployment; methods and headers are the API contract |
| Validated **at startup** | `*`, paths and typos fail the deploy, not the users (trap 2, trap 5) |
| **Filter**, highest precedence | Errors and future Security rejections carry CORS headers (§3) |
| Only `/api/**` | Actuator isn't for browsers (tested: no CORS headers there) |
| **No credentials** | Bearer token in `Authorization`, not cookies. Cookies would also need CSRF protection |
| Bean named `corsConfigurationSource` | Spring Security's `http.cors()` picks it up, so there's one source of truth |

### Verified in the packaged jar (`prod` profile)

```text
[default]                       preflight from https://app.example.com → 403
                                log: "CORS: no cross-origin browser access to /api/** (jobfinder.cors.allowed-origins is empty)"
[JOBFINDER_CORS_ALLOWEDORIGINS=https://app.example.com]
                                preflight → 200, Access-Control-Allow-Origin: https://app.example.com
                                log: "CORS: allowing origins [https://app.example.com] for /api/**"
[JOBFINDER_CORS_ALLOWEDORIGINS='*']
                                app refuses to start (exit 1):
                                Invalid CORS origin '*': expected scheme://host[:port], e.g. https://app.example.com …
```

Environment variable name: Boot's relaxed binding turns `jobfinder.cors.allowed-origins` into
`JOBFINDER_CORS_ALLOWEDORIGINS` (dots → `_`, dashes removed, upper case). That's why the prefix is the single
word `jobfinder`.

---

## 6. CORS and Spring Security (coming later)

- Preflights carry **no** `Authorization` header. If Security authenticates them first, every preflight gets
  `401` and the frontend can't do anything. `http.cors(Customizer.withDefaults())` makes Security handle CORS
  (using the `corsConfigurationSource` bean) **before** authentication.
- Security's 401/403 come from a filter. That's exactly the case the filter-based setup covers (§3).
- **CORS ≠ CSRF protection.** CSRF matters when browsers send credentials (cookies) automatically. With bearer
  tokens in a header, browsers don't attach them automatically, which is one reason Job Finder chose tokens
  (ADR 0007, decision 6).

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| `allowedOrigins("*")` "to make it work" | Any website can read responses; with credentials, every request fails with 500 (verified) |
| MVC-only CORS with Spring Security | Security's 401/403 have no CORS headers, so the frontend sees opaque "CORS errors" (verified) |
| Bare `CorsConfiguration` without allowed headers | JSON preflights fail with 403 (verified) |
| Forgetting `Access-Control-Expose-Headers` | Frontend can't read `Location`, `ETag`, custom headers (verified) |
| `@CrossOrigin` sprinkled on controllers | Inconsistent policy that's hard to audit |
| Thinking CORS protects the API | Non-browser clients ignore it (verified); use authentication |
| Origin with a path (`https://app.example.com/app`) | Never matches; rejected by startup validation (verified) |
| Reflecting any `Origin` back as allowed | Same as `*`, but also works with credentials: a serious hole |
| Very low or no `Max-Age` | A preflight before almost every request: double latency |
| Authenticating preflight requests | Every preflight 401, nothing works |

## Interview questions

**Q: What is CORS and why does it exist?**
Browsers block pages from reading responses from other origins (same-origin policy) to protect users. CORS is
the protocol by which a server tells the browser which other origins may read its responses.

**Q: What is a preflight request?**
An `OPTIONS` request the browser sends before a "non-simple" cross-origin request (e.g. JSON body, `PUT`/`DELETE`,
`Authorization` header) to ask whether the actual request is allowed. The answer can be cached via `Access-Control-Max-Age`.

**Q: Does CORS protect my API from unauthorized access?**
No. It's enforced only by browsers. Any non-browser client can call the API. CORS protects users from malicious
websites reading data with their session. Use authentication and authorization to protect the API.

**Q: Why can't you use `*` with credentials?**
The spec forbids it: allowing every website to make credentialed requests and read the responses would defeat
the same-origin policy. Spring throws `IllegalArgumentException` when it processes such a request.

**Q: `@CrossOrigin` / `CorsRegistry` vs `CorsFilter`?**
The first two are Spring MVC-level and only affect responses produced by Spring MVC. `CorsFilter` runs as a
servlet filter, before everything else, so it also covers responses produced by other filters, such as Spring Security.

**Q: Why expose headers?**
JavaScript can only read a few safelisted response headers. Others (`Location`, `ETag`, custom ones) must be
listed in `Access-Control-Expose-Headers`.

**Q: How does CORS interact with Spring Security?**
Enable `http.cors()` so CORS is processed before authentication (preflights have no credentials). Security uses a
`CorsConfigurationSource` bean.
