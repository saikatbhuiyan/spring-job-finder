# ADR 0007: CORS policy for the REST API

- **Status:** Accepted; extended by [ADR 0012](0012-rate-limiting.md) (`Retry-After` exposed), [ADR 0013](0013-request-correlation.md) (`X-Trace-Id` exposed) and [ADR 0014](0014-idempotency-keys.md) (`Idempotency-Key` allowed, `Idempotent-Replayed` exposed)
- **Date:** 2026-11-22

## Context

A browser frontend (SPA) served from another origin, e.g. `https://app.example.com` calling
`https://api.example.com`, may only read API responses if the API answers with CORS headers. Decisions are
needed on *which* origins, *how* CORS is applied, and what is exposed. A scratch app and the project's
tests (`learning/32-cors.md`) showed:

- Spring MVC's `CorsRegistry` only adds CORS headers to responses produced by Spring MVC. A `401` sent by a
  **filter** (what Spring Security does) had **no** CORS headers, so the browser would hide it and the
  frontend could not tell "logged out" from "network error". A `CorsFilter` covered it.
- `new CorsConfiguration()` allows **no request headers**. Even `Content-Type: application/json` failed its
  preflight (`403`) until headers were listed. (`CorsRegistry` silently applies permissive defaults instead.)
- `allowedOrigins("*")` with `allowCredentials(true)` starts fine, then **every** cross-origin request fails
  with a `500` (`IllegalArgumentException`).
- Browsers hide response headers that aren't exposed: `Location` (for `201`) and the API retirement headers
  `Deprecation` / `Sunset` / `Link` (ADR 0002).

## Decision

1. **Explicit allowlist of exact origins**, per environment, in `jobfinder.cors.allowed-origins`
   (`JOBFINDER_CORS_ALLOWEDORIGINS` as an environment variable). **Default: empty** (no cross-origin
   access). The `dev` profile allows the local frontend dev servers (`http://localhost:5173`,
   `http://localhost:3000`). Production names its frontend explicitly.
2. **Validated at startup** (`CorsProperties`): only `http(s)://host[:port]` is accepted. Wildcards, paths,
   user info and queries fail startup with a clear message, instead of failing requests later.
3. **Applied by a servlet `CorsFilter`** at the highest precedence, backed by a `CorsConfigurationSource`
   bean named `corsConfigurationSource`, so every response (errors and filter rejections included) carries
   CORS headers. Spring Security's `http.cors()` will use the same bean.
4. **Scope: `/api/**` only.** Actuator isn't for browsers.
5. **Contract in code, not configuration:** methods `GET, POST, PUT, PATCH, DELETE`; request headers
   `Content-Type, Authorization`; exposed headers `Location, Deprecation, Sunset, Link`; preflight cache
   `max-age` 1 hour (configurable).
6. **No credentials** (`Access-Control-Allow-Credentials` is not sent). The API will authenticate with a bearer
   token in the `Authorization` header, not cookies. Cookie-based auth would require revisiting this
   together with CSRF protection.
7. Requests from origins that aren't allowed are **rejected with `403`** before any handler runs, so even
   "simple" cross-origin POSTs, which browsers send without a preflight, never execute.
8. Locked in by `CorsTests` (9 HTTP tests with browser headers) and `CorsPropertiesTests` (11 startup
   validation cases). Mutation checks: switching to `CorsRegistry`, or dropping allowed or exposed headers, each
   fails exactly the test that guards it.

## Consequences

**Positive**
- Secure by default. A new environment can't accidentally be open to every website.
- Frontends can read every response, including Problem Details for 401/404/500 and `Location`.
- One CORS configuration for MVC today and Spring Security later.
- Misconfiguration is caught at deploy time (startup failure), not by users.

**Negative / accepted trade-offs**
- Every frontend origin (including preview deployments) must be listed explicitly. Wildcard patterns
  (`https://*.example.com`) are deliberately unsupported for now.
- New request headers (e.g. `Idempotency-Key`) or exposed headers (e.g. `ETag`) need a code change here.

**Important: CORS is not security for the API.** It only tells *browsers* which pages may read responses.
`curl` and servers ignore it (tested). Data is protected by authentication and authorization (Spring
Security, roadmap).

**Follow-ups**
- Spring Security: enable `http.cors(Customizer.withDefaults())` so preflights pass before authentication.
  Then decide whether the standalone `CorsFilter` stays (harmless: Spring skips responses that already have
  CORS headers) or is removed.
- Behind a reverse proxy: configure `server.forward-headers-strategy` so same-origin detection uses the
  public scheme and host.

## References

- Fetch Standard, "CORS protocol" (WHATWG); MDN "Cross-Origin Resource Sharing (CORS)"
- Spring Framework reference: "CORS"
- Learning note: `learning/32-cors.md`
