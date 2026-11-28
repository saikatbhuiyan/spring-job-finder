# 42 — Spring Security + JWT: Authentication and Authorization

## Goal

Job seekers and employers will log in, and employers may only edit their own jobs. This note explains,
from first principles, how an API knows **who** is calling (authentication) and **what** they may do
(authorization), what a JWT is and how it's validated, where tokens come from (OAuth2 / OpenID Connect),
how Spring Security works inside, and how passwords are stored. Every trap below was reproduced.

Verified in a scratch app (Spring Boot 4.2.0, **Spring Security 7.2.0**, Nimbus JOSE+JWT 10.9.1, JDK 25.0.1,
Apple M1 Max). Status: 🧪 verified in a demo; **not in the project yet** (see [In this project](#in-this-project)).

---

## 1. Authentication vs authorization, `401` vs `403`

| | Authentication (*authN*) | Authorization (*authZ*) |
|-|--------------------------|-------------------------|
| Question | **Who are you?** | **Are you allowed to do this?** |
| Evidence | password, token, certificate | roles, scopes, ownership of the data |
| Fails with | **`401 Unauthorized`** (badly named: it means *unauthenticated*) | **`403 Forbidden`** |
| Client should | log in / get a new token, then retry | not retry: the answer won't change |

Authentication always comes first: you can't decide what someone may do before you know who they are.

Verified (§7 has the setup): the same `PUT /api/jobs/1`, once with no token and once with a valid token that
lacks the `jobs:write` scope:

```text
PUT /api/jobs/1  (no token)          -> 401  WWW-Authenticate: Bearer resource_metadata="http://localhost:8091/.well-known/oauth-protected-resource"
PUT /api/jobs/1  (scope jobs:read)   -> 403  WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token."
```

A `401` must carry a **`WWW-Authenticate`** header saying *how* to authenticate (`Basic`, `Bearer`); that's
the HTTP standard (RFC 9110), not decoration.

---

## 2. How a client proves who it is on every request

HTTP is **stateless**: each request arrives alone. After logging in, the client must send *something* with
every request. Two families:

| | Session (server-side state) | Token (self-contained) |
|-|-----------------------------|------------------------|
| After login the client gets | a random session id in a cookie (`JSESSIONID`) | a signed token (a JWT) |
| The server stores | the session (who, roles) in memory or Redis | **nothing**: everything is in the token |
| Each request | cookie → look the session up | `Authorization: Bearer <token>` → verify the signature |
| Logout / revoke | delete the session: immediate | hard: the token stays valid until it expires (§12) |
| Several instances | need a shared session store | any instance can verify the token |
| Browser risk | cookies are sent automatically → **CSRF** (§10) | headers aren't sent automatically → no CSRF |
| Typical for | server-rendered web apps | APIs, mobile apps, single-page apps, service-to-service |

Job Finder is an API used by a frontend and possibly mobile apps, so **tokens**, with no server sessions
(`SessionCreationPolicy.STATELESS`). Verified: with a bearer token, no response sets a cookie (§6 shows the
session-based default doing the opposite).

---

## 3. What a JWT is

A **JWT** (JSON Web Token, RFC 7519, "jot") is three base64url-encoded parts joined by dots:
`header.payload.signature`. A real one from the demo (573 characters):

```text
eyJraWQiOiJtYWluLWtleSIsImFsZyI6IlJTMjU2In0.eyJpc3MiOiJodHRwczovL2F1dGguam9iZmlu...
```

Decoded (just base64, **no key needed**):

```json
{"kid": "main-key", "alg": "RS256"}
{"iss": "https://auth.jobfinder.test", "sub": "alice", "aud": "job-finder-api", "exp": 1795803012, "iat": 1795802112, "scope": "jobs:read jobs:write"}
```

| Part | Contents |
|------|----------|
| Header | `alg`: how it's signed (`RS256` = RSA + SHA-256); `kid`: which key signed it |
| Payload: **claims** | `iss` issuer (who made it), `sub` subject (the user), `aud` audience (which API it's for), `exp` expiry and `iat` issued-at (seconds since 1970), plus custom claims (`scope`, `roles`) |
| Signature | the header + payload signed with the issuer's **private** key (342 characters here) |

Two consequences:

1. **Signed, not encrypted.** Anyone holding the token can read it (we just did). Never put secrets or personal
   data in claims.
2. **Tamper-proof.** Changing one character of the payload breaks the signature; the API detects it with the
   issuer's **public** key (§7). That's why the API can trust a token without calling anyone or storing anything.

**Asymmetric (`RS256`, `ES256`) vs symmetric (`HS256`).** With RSA/EC, only the issuer has the private key and
every API verifies with the public key. With HMAC, the same secret signs and verifies, so every API that can
verify a token can also **forge** one. Use asymmetric keys whenever more than one party is involved.

---

## 4. Where tokens come from: OAuth2 and OpenID Connect

**OAuth 2.0** (RFC 6749) is the standard for *issuing* access tokens; **OpenID Connect** (OIDC) adds login
(who the user is: an ID token, `/userinfo`) on top. The roles:

| Role | Who | In Job Finder |
|------|-----|---------------|
| Resource owner | the user | a job seeker or employer |
| Client | the app acting for the user | the web frontend, a mobile app |
| **Authorization server** (identity provider, IdP) | logs users in, issues tokens | Keycloak, Auth0, Okta, Entra ID, Spring Authorization Server |
| **Resource server** | the API that accepts tokens | **this API** |

The usual flows:

- **Authorization code + PKCE**: a user logs in on the IdP's page (the API never sees the password); the client
  gets an access token. For browsers and mobile apps.
- **Client credentials**: a service gets a token for itself (no user). For service-to-service calls.
- ~~Password grant~~ (client sends the user's password to get a token): forbidden by the OAuth security best
  practice (RFC 9700) and dropped from the OAuth 2.1 draft; don't build it.

### Resource server vs "issue our own JWTs"

Many tutorials have the API store passwords, check them on `POST /login` and sign its own JWTs. Then the API owns
password storage, hashing (§11), lockout after failed attempts, password reset, MFA, signing-key rotation,
refresh tokens and revocation: each a classic source of security bugs. The production pattern is to let a
dedicated identity provider do all of that and make the API a **resource server** that only *validates*
tokens. Spring Boot's starter for that is `spring-boot-starter-security-oauth2-resource-server` (Boot 4 renamed
the security starters with a `security-` prefix; verified in the Boot 4.2.0 BOM).

---

## 5. How Spring Security works inside

Spring Security is a chain of **servlet filters** that runs **before** Spring MVC, so before any controller and
before any `@ControllerAdvice`:

```text
request ─▶ Tomcat ─▶ other filters ─▶ FilterChainProxy ─▶ SecurityFilterChain (≈15 filters) ─▶ DispatcherServlet ─▶ controller
                                           (order -100)        authenticate, authorize
```

Vocabulary:

| Term | Meaning |
|------|---------|
| `SecurityFilterChain` | your configuration: which filters, which URL rules (a `@Bean`) |
| `Authentication` | who is calling and their authorities (`JwtAuthenticationToken` for a JWT) |
| `GrantedAuthority` | one permission string: `SCOPE_jobs:write`, `ROLE_ADMIN` |
| `SecurityContextHolder` | holds the current `Authentication` for the request's thread |
| `AuthenticationEntryPoint` | writes the `401` (and the `WWW-Authenticate` challenge) |
| `AccessDeniedHandler` | writes the `403` |

The filters in the demo's resource-server chain, in order (verified, DEBUG log of `DefaultSecurityFilterChain`):

```text
DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter, HeaderWriterFilter,
CsrfFilter, LogoutFilter, OAuth2ProtectedResourceMetadataFilter, BearerTokenAuthenticationFilter,
RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter
```

- `BearerTokenAuthenticationFilter` reads `Authorization: Bearer …`, validates the JWT, sets the `Authentication`.
- `AnonymousAuthenticationFilter`: no credentials → an "anonymous" `Authentication`, not `null`.
- `AuthorizationFilter` (last) checks the URL rules; on failure `ExceptionTranslationFilter` calls the entry
  point (`401`, anonymous caller) or the access-denied handler (`403`, authenticated caller).
- **New in Spring Security 7**: `OAuth2ProtectedResourceMetadataFilter` serves RFC 9728 metadata at
  `/.well-known/oauth-protected-resource` (verified: `{"resource":"http://localhost:8091","bearer_methods_supported":["header"],...}`)
  and the `401` challenge links to it; and every `Authentication` gets a **factor authority** (`FACTOR_BEARER`,
  `FACTOR_PASSWORD`, verified), the basis of Spring Security 7's multi-factor authorization.

---

## 6. What you get with zero configuration (verified)

Only `spring-boot-starter-security` added, no configuration:

```text
WARN  .s.a.UserDetailsServiceAutoConfiguration :

Using generated security password: <random UUID>

This generated password is for development use only. Your security configuration must be updated before running your application in production.
```

```text
$ curl -i localhost:8091/api/jobs
HTTP/1.1 401
Set-Cookie: JSESSIONID=9F9702D610243C3F7CC4B354E206A19B; Path=/; HttpOnly
WWW-Authenticate: Basic realm="Realm", charset="UTF-8"
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Length: 0
```

- **Secure by default**: every URL requires authentication, even ones you didn't think about.
- Security **headers** are added to every response (`nosniff`, `DENY` framing, no caching).
- An empty body: **not Problem Details** (§9).
- A browser (`Accept: text/html`) gets `302` with `Location: /login`, a generated "Please sign in" form (verified).
- A session cookie, even for a rejected request (it saves the request to replay after login).
- With the **resource-server starter** on the classpath, Boot prints **no** generated password: the default user
  backs off (verified), so with no JWT configuration nobody can get in at all.

### ⚠️ A CSRF rejection disguised as a `401` (verified)

With the generated password, `GET /api/me` → `200`, but `PUT /api/jobs/1` with the **same correct password**
→ `401`. The DEBUG log shows why:

```text
o.s.security.web.FilterChainProxy        : Securing PUT /api/jobs/1
o.s.security.web.csrf.CsrfFilter         : Invalid CSRF token found for http://localhost:8091/api/jobs/1
o.s.s.w.access.AccessDeniedHandlerImpl   : Responding with 403 status code
o.s.security.web.FilterChainProxy        : Securing GET /error
o.s.s.w.a.AnonymousAuthenticationFilter  : Set SecurityContextHolder to anonymous SecurityContext
...                                      : Using default entry point ...BasicAuthenticationEntryPoint
```

`CsrfFilter` runs **before** the password is read and answers `403`; Tomcat forwards that to `/error`, which is
secured again **as an anonymous request**, so the client sees `401`. The real cause is only in the log. Lesson:
when Spring Security answers something surprising, turn on `logging.level.org.springframework.security=DEBUG`.

---

## 7. The API as a resource server: validating JWTs (verified)

The demo's configuration (a scratch app; the identity provider is simulated by an endpoint that signs tokens
with an RSA key pair):

```java
@Bean
JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withPublicKey(identityProviderPublicKey).build();
}

@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/jobs").permitAll()
                    .requestMatchers(HttpMethod.PUT, "/api/**").hasAuthority("SCOPE_jobs:write")
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
}
```

A valid token → `200`, and what Spring built from it:

```json
{"name":"alice","authorities":["FACTOR_BEARER","SCOPE_jobs:read","SCOPE_jobs:write"],"type":"JwtAuthenticationToken"}
```

The `name` is the `sub` claim; each word of the `scope` claim became a `SCOPE_` authority.

Then every way an attacker or a bug could present a bad token:

| # | Token | Result | `error_description` |
|---|-------|--------|---------------------|
| 1 | payload edited (`sub` alice → bob), signature kept | **401** | `Signed JWT rejected: Invalid signature` |
| 2 | signed with another key | **401** | `Signed JWT rejected: Invalid signature` |
| 3 | `alg: none`, no signature | **401** | `Unsupported algorithm of none` |
| 4 | expired 5 minutes ago | **401** | `Jwt expired at 2026-11-27T18:00:27Z` |
| 5 | expired **30 seconds** ago | ⚠️ **200** | |
| 6 | issued by `https://evil.example` | ⚠️ **200** | |
| 7 | for another API (`aud: payments-api`) | ⚠️ **200** | |
| 8 | `not-a-jwt` | **401** | `Malformed token` |

The signature checks hold. Three tokens that should fail are accepted:

- **#5: clock skew.** The default validator allows **60 seconds** of skew between servers' clocks. Verified:
  expired 30 s ago → `200`, 61 s ago → `401`. Normal and intended; just know an access token lives up to a
  minute longer than its `exp`.
- **#6 and #7: `withPublicKey(...)` checks only the signature and the timestamps.** It doesn't know which
  issuer or which API to expect. Point #7 is the dangerous one: if one identity provider serves several APIs, a
  token issued for the payments API is accepted by Job Finder. The fix, verified:

```java
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("https://auth.jobfinder.test"),   // timestamps + iss
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains("job-finder-api"))));
```

```text
#6 -> 401  error_description="An error occurred while attempting to decode the Jwt: The iss claim is not valid"
#7 -> 401  error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid"
```

With a real identity provider you configure properties instead of a bean:
`spring.security.oauth2.resourceserver.jwt.issuer-uri` (Boot fetches the provider's public keys from its JWKS
endpoint, follows key rotation, and validates `iss`) and `...jwt.audiences` (validates `aud`). Verified against
Keycloak in [note 43 §8](43-oauth2-oidc-and-keycloak.md#8-the-api-trusts-keycloak-verified).

---

## 8. Authorization

### 8.1 URL rules, scopes and roles

- **Scopes** say what the *client application* may do on the user's behalf (`jobs:write`); Spring maps the
  `scope` claim to `SCOPE_…` authorities by default.
- **Roles** say what the *user* is (`EMPLOYER`, `ADMIN`). Identity providers put them in a custom claim
  (`roles`, Keycloak's `realm_access.roles`), which Spring **ignores by default**. Verified: a token with
  `"roles": ["ADMIN"]` gives the authorities `FACTOR_BEARER, SCOPE_jobs:read` only, and `/api/admin/stats` → `403`.
  Map the claim yourself:

```java
JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
roles.setAuthoritiesClaimName("roles");
roles.setAuthorityPrefix("ROLE_");
// combined with the default scope converter in a JwtAuthenticationConverter
```

Then: authorities `FACTOR_BEARER, ROLE_ADMIN, SCOPE_jobs:read`, and `/api/admin/stats` → `200 {"jobs":2}`.

`hasRole("ADMIN")` checks for the authority `ROLE_ADMIN`; `hasAuthority("ROLE_ADMIN")` is the same thing
spelled out. Writing `hasRole("ROLE_ADMIN")` **fails at startup** (verified):

```text
IllegalArgumentException: ROLE_ADMIN should not start with ROLE_ since ROLE_ is automatically prepended when using hasAnyRole. Consider using hasAnyAuthority instead.
```

### 8.2 ⚠️ Roles are not enough: ownership (verified)

Bob is an employer with `jobs:write`. Job 1 belongs to Alice. The URL rule only checks the scope:

```text
PUT /api/jobs/1     (bob)  -> 200  {"updated":1,"owner":"alice","by":"bob"}
```

Bob edited Alice's job. This is **Broken Object Level Authorization (BOLA)**, also called IDOR: number one in
the OWASP API Security Top 10. A role says *what kind* of thing you may do, never *to which records*. That needs a
check against the data:

```java
if (owner == null || !owner.equals(auth.getName())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
```

```text
PUT /api/v2/jobs/1  (bob)  -> 404   (alice's job)
PUT /api/v2/jobs/2  (bob)  -> 200  {"updated":2,"owner":"bob","by":"bob"}
PUT /api/v2/jobs/99 (bob)  -> 404   (doesn't exist)
```

Not-yours answers **`404`**, exactly like doesn't-exist, so the API doesn't reveal which ids exist. The check
belongs in the **application layer** (the use case loads the job and compares its owner with the caller), not
in a controller, so every way of reaching the use case is covered.

### 8.3 Deny by default (verified)

Someone adds `GET /api/reports/salaries` and forgets to write a rule for it. With the usual last rule
`anyRequest().authenticated()`:

```text
GET /api/reports/salaries  (carol, a job seeker)  -> 200  {"alice":95000,"bob":120000}
```

Any logged-in user can read it. With `anyRequest().denyAll()`, a forgotten rule fails **closed**:

```text
GET /api/reports/salaries  (dana, an admin)  -> 403
```

⚠️ But `denyAll()` also applies to Tomcat's internal forward to `/error` (the *ERROR dispatch*), so every error
became a `403` (verified: Bob's `404` for Alice's job came back as `403`). Permit that dispatch first:

```java
auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
```

Then the `404` is a `404` again (verified), and an unknown URL is `403` with a token, `401` without (it's denied
before routing, so it doesn't reveal which URLs exist).

### 8.4 Method security

`@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")` on a method: checked when the method is called,
not by URL. Verified: a job seeker → `403 insufficient_scope`, an admin → `200`. Useful in the application
layer; ownership rules can use it too (`@PreAuthorize("@jobAccess.isOwner(#id, authentication)")`), though a
plain check in the use case is easier to read and test.

---

## 9. Errors: Problem Details from security filters

Security errors happen in filters, **before** Spring MVC, so `@RestControllerAdvice` never sees them; the
default `401` has an empty body. To return RFC 9457 Problem Details, give the resource server your own
`AuthenticationEntryPoint` and `AccessDeniedHandler`.

⚠️ Writing only the body loses the challenge header (verified):

```text
own entry point, body only        -> 401  WWW-Authenticate: (none)   {"type":"about:blank","title":"Unauthorized","status":401,...}
delegating to Spring's first      -> 401  WWW-Authenticate: Bearer error="invalid_token", error_description="...Jwt expired at 2026-11-27T18:01:31Z"   {"type":"about:blank",...}
```

So call `BearerTokenAuthenticationEntryPoint.commence(...)` (and `BearerTokenAccessDeniedHandler.handle(...)`)
first, then write the body.

### ⚠️ A catch-all `@ExceptionHandler(Exception.class)` turns `403` into `500` (verified)

`@PreAuthorize` denials are different: they're thrown **inside** the controller call, as
`AuthorizationDeniedException`, so they travel through Spring MVC's exception handling first. With the classic
"safe 500" advice:

```text
GET /api/method-secured  (job seeker)  -> 500  {"detail":"Internal error: AuthorizationDeniedException","status":500,...}
```

Without the advice, the same request → `403` (Spring Security's `ExceptionTranslationFilter` handles it).
The fix is to let security exceptions pass (rethrow `AccessDeniedException` and `AuthenticationException` from
the handler, or map them explicitly), and test it.

---

## 10. CSRF and CORS with bearer tokens

- **CSRF** (cross-site request forgery) works because browsers attach **cookies** automatically to requests made
  by any page. A bearer token is sent only when the client's code adds the header, so a stateless token API isn't
  exposed. Verified: with CSRF protection still on, `PUT` with a bearer token → `200`; Spring Security's resource
  server skips the CSRF check for requests that carry a bearer token. (Store tokens in a cookie instead and CSRF is
  back.)
- **CORS preflight**: a browser sends `OPTIONS` *without* credentials before a cross-origin `PUT` with an
  `Authorization` header. Verified: without `http.cors(...)` → **`401`** (the browser blocks the real request);
  with `http.cors(Customizer.withDefaults())` and a `CorsConfigurationSource`:

```text
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,PUT
Access-Control-Allow-Headers: authorization
```

---

## 11. If you store passwords: hashing (verified)

A resource server stores no passwords. If you ever do (or to understand what the identity provider does), never
store them in plain text or with a fast hash. Measured on one core:

| Algorithm | Time per hash | Guesses per second for an attacker with one core |
|-----------|---------------|---------------------------------------------------|
| SHA-256 (fast, unsalted) | 0.00005 ms | **21,834,450** |
| PBKDF2 (Spring defaults) | 47.0 ms | ~21 |
| BCrypt strength 10 (default) | 77.1 ms | ~13 |
| BCrypt strength 12 | 306.6 ms | ~3 |
| BCrypt strength 14 | 1201.9 ms | <1 |

- Password hashes are **deliberately slow** so a leaked database can't be brute-forced; each strength step doubles
  the cost (10 → 12 → 14: ×4 each time, as measured). Pick the slowest your login latency allows (~100–300 ms).
- They're **salted**: the same password gives a different hash each time (verified), so identical passwords
  don't look identical and precomputed tables are useless.
- Use Spring's `DelegatingPasswordEncoder` (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`): the
  hash records its algorithm, so you can upgrade later without breaking old hashes:

```text
{bcrypt}$2a$10$iiYqhBLPLySGS8JdcwZyZOxs8lMPXMVzRmCpeYpqnOOCXSKP6FuKq
{bcrypt}$2a$10$u1wZBw.g4NARGAi2vBNo3uiCb95KVgxe0cVV/SelEOUf5LZ6xv0DG      ← same password
same password, same hash? false; matches #1? true; wrong password matches? false
```

- A hash without the `{id}` prefix is rejected: `Given that there is no default password encoder configured,
  each password must have a password encoding prefix.`
- BCrypt only uses the first **72 bytes**; Spring Security now refuses longer input (verified:
  `password cannot be more than 72 bytes`) instead of silently ignoring the rest.
- **Argon2** (the modern recommendation) needs Bouncy Castle on the classpath; without it:
  `NoClassDefFoundError: org/bouncycastle/crypto/params/Argon2Parameters$Builder` (verified).

---

## 12. Token lifetime and revocation

A JWT can't be "logged out": it is valid until `exp` (plus the skew) on every server, because no server stores
it. The standard answer:

- **Short-lived access tokens** (5–15 minutes) limit the damage of a stolen token.
- **Refresh tokens** (longer-lived, kept by the client, sent only to the identity provider) get new access tokens;
  the provider can revoke *those* immediately.
- If a change must take effect at once (an employer's account suspended), check it in the application (the user
  is looked up anyway for ownership), or use **opaque tokens + introspection**: the API asks the provider about
  every token (always current, but a network call per request).

---

## In this project

**Not applied yet.** There's nothing to protect until the `job` module exists (roadmap: CI, then the `job` module
with PostgreSQL, then security). The decision will be an ADR then; what this study means for it:

1. **Resource server, not self-issued tokens** (§4): `spring-boot-starter-security-oauth2-resource-server`, a real
   identity provider (Keycloak in docker-compose for local runs, Testcontainers in tests), `issuer-uri` **and**
   `audiences` configured (§7 #6–#7).
2. **Roles from the token** mapped explicitly (§8.1), **ownership in the use cases** (§8.2), `anyRequest().denyAll()`
   with the ERROR dispatch permitted (§8.3), plus an ArchUnit/test guard that every endpoint has a rule.
3. **Problem Details for `401`/`403`** (ADR 0003, 0006) through an entry point and access-denied handler that keep
   `WWW-Authenticate` (§9) and include the `traceId` (ADR 0013).
4. ⚠️ **[GlobalExceptionHandler.java:139](../src/main/java/com/shahabuddin/jobfinder/shared/api/GlobalExceptionHandler.java#L139)
   has `@ExceptionHandler(Exception.class)` at the highest precedence**: exactly the setup that turned a
   `@PreAuthorize` denial into a `500` in §9. It must let security exceptions through, with a test, before any
   method security is used.
5. **The ERROR dispatch**: our `ProblemDetailsErrorController` serves `/error` (ADR 0006); under `denyAll()` it
   must stay reachable or every error becomes `403` (§8.3).
6. **Filter order** (each runs before the next):

   | Order | Filter | Why there |
   |-------|--------|-----------|
   | `HIGHEST_PRECEDENCE` | CORS ([CorsConfig.java](../src/main/java/com/shahabuddin/jobfinder/shared/api/CorsConfig.java)) | a preflight has no token (§10); also `http.cors(withDefaults())` (ADR 0007) |
   | `+2` | trace ID ([TracingConfig.java](../src/main/java/com/shahabuddin/jobfinder/shared/api/TracingConfig.java)) | `401`/`403` get an `X-Trace-Id` too |
   | `+10` | rate limit ([RateLimitConfig.java](../src/main/java/com/shahabuddin/jobfinder/shared/api/RateLimitConfig.java)) | token floods are limited before signature checks |
   | `-100` | **Spring Security** | |
   | `0` | idempotency ([IdempotencyConfig.java](../src/main/java/com/shahabuddin/jobfinder/shared/api/IdempotencyConfig.java)) | needs the user: keys become per user (ADR 0014) |

7. Follow-ups already noted in ADRs: per-user rate limits (ADR 0012), idempotency keys per user (ADR 0014), a
   `bearerAuth` security scheme and `401`/`403` in the OpenAPI document (ADR 0016).

---

## Run it / verify it

The demo was a throwaway app (not in this repository): Boot 4.2.0 with `spring-boot-starter-webmvc`,
`spring-boot-starter-security` and `spring-boot-starter-security-oauth2-resource-server`; a `/dev/token`
endpoint that signs JWTs (`NimbusJwtEncoder` with an RSA key, plus a second "attacker" key); switches for each
experiment. The commands, in the order of the sections above:

```bash
# §3: get a token and read it (no key needed)
T=$(curl -s "localhost:8091/dev/token?sub=alice&scope=jobs:read%20jobs:write")
python3 -c 'import sys,base64,json; p=sys.argv[1].split(".")[1]; print(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))))' "$T"

# §1, §7: call the API
curl -i -H "Authorization: Bearer $T" localhost:8091/api/me     # 200 + authorities
curl -i localhost:8091/api/me                                    # 401 + WWW-Authenticate: Bearer
curl -i -H "Authorization: Bearer $(curl -s 'localhost:8091/dev/token?sub=alice&aud=payments-api')" localhost:8091/api/me   # 200 until aud is validated

# §6: see why Spring Security answered what it did: start the app with
#   --logging.level.org.springframework.security=DEBUG
```

Real outputs are quoted in each section. The password measurements (§11) ran as a plain `main` method against
`spring-security-crypto` 7.2.0, ten hashes per algorithm after a warm-up (three at strength 14).

---

## Common mistakes

| Mistake | What happens (verified where marked) | Fix |
|---------|--------------------------------------|-----|
| `NimbusJwtDecoder.withPublicKey(...)` alone | Tokens from another issuer or for another API accepted (verified) | Validate `iss` and `aud` (`issuer-uri` + `audiences`) |
| Expecting `exp` to be exact | Token accepted up to 60 s after expiry (verified) | Normal clock skew; keep access tokens short-lived |
| Role checks only | Bob edits Alice's job (verified, OWASP API1 BOLA) | Ownership check in the use case; `404` for not-yours |
| `anyRequest().authenticated()` as the last rule | A forgotten endpoint is open to every user (verified) | `anyRequest().denyAll()` |
| `denyAll()` without permitting the ERROR dispatch | Every `404`/`400` becomes `403` (verified) | `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` |
| `roles` claim expected to just work | `403` for admins (verified) | Map it with a `JwtGrantedAuthoritiesConverter` |
| `hasRole("ROLE_ADMIN")` | Startup fails (verified) | `hasRole("ADMIN")` or `hasAuthority("ROLE_ADMIN")` |
| Catch-all `@ExceptionHandler(Exception.class)` | `@PreAuthorize` denial → `500` (verified) | Rethrow/map `AccessDeniedException`, `AuthenticationException` |
| Custom entry point writing only a body | `WWW-Authenticate` lost (verified) | Delegate to `BearerTokenAuthenticationEntryPoint` first |
| No `http.cors(...)` | Browser preflight → `401` (verified) | `http.cors(withDefaults())` + `CorsConfigurationSource` |
| Trusting a surprising `401` | It was a CSRF `403` re-secured on `/error` (verified) | DEBUG log `org.springframework.security` |
| Secrets or personal data in JWT claims | Anyone with the token reads them (verified: plain base64) | Only ids, roles, scopes |
| `HS256` shared secret across services | Every verifier can forge tokens | `RS256`/`ES256`; the IdP keeps the private key |
| Fast hash (SHA-256, MD5) for passwords | 21.8 million guesses/s on one core (measured) | BCrypt/Argon2 via `DelegatingPasswordEncoder` |
| Building `POST /login` that signs JWTs | You own hashing, lockout, MFA, rotation, revocation | A real identity provider + resource server |

---

## Interview questions

**Q: Authentication vs authorization?**
Authentication establishes who the caller is; authorization decides what they may do. Authentication fails with
`401` (plus a `WWW-Authenticate` challenge), authorization with `403`.

**Q: What's in a JWT, and is it encrypted?**
A header (algorithm, key id), claims (`iss`, `sub`, `aud`, `exp`, `iat`, custom ones) and a signature, each
base64url-encoded. It's signed, not encrypted: anyone can read it, nobody can change it without the key.

**Q: Which claims must a resource server validate?**
The signature (with the issuer's public key), `exp`/`nbf` (with a small clock skew), `iss` (the expected issuer)
and `aud` (this API). Spring's `withPublicKey` decoder checks only the signature and timestamps.

**Q: Sessions or JWTs for a REST API?**
Tokens: stateless, work across instances and for mobile/service clients, no CSRF. The cost is revocation:
mitigated with short-lived access tokens and refresh tokens, or opaque tokens with introspection.

**Q: Why use an identity provider instead of issuing your own tokens?**
Password storage, MFA, lockout, key rotation and revocation are hard to get right; a provider does them and the
API only validates tokens (OAuth2 resource server). The API never sees passwords.

**Q: Roles vs scopes vs ownership?**
Scopes limit what a client app may do for a user, roles describe the user, ownership decides which records they
may touch. Only ownership stops one employer from editing another's job (BOLA, OWASP API #1).

**Q: Where does Spring Security run, and why does that matter for error handling?**
In servlet filters before the `DispatcherServlet`, so `@ControllerAdvice` never sees filter-level `401`/`403`:
use an `AuthenticationEntryPoint` and `AccessDeniedHandler`. Method-security denials are thrown inside the
controller call, so a catch-all exception handler can turn them into `500`.

**Q: Do you need CSRF protection for a JWT API?**
Not when the token is sent in the `Authorization` header (browsers don't attach it automatically). If the token is
stored in a cookie, yes.

**Q: How should passwords be stored?**
With a slow, salted, adaptive hash (BCrypt, Argon2, PBKDF2) through a `DelegatingPasswordEncoder`, so the algorithm
can be upgraded. Never plain text or a fast hash.

**Q: What does `hasRole("ADMIN")` check?**
The authority `ROLE_ADMIN`. Spring adds the prefix; passing `"ROLE_ADMIN"` to `hasRole` fails at startup.
