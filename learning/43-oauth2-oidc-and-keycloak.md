# 43 — OAuth 2.0, OpenID Connect and Keycloak

## Goal

[Note 42](42-spring-security-and-jwt.md) made the API *validate* tokens. This note covers where the tokens come
from: what OAuth 2.0 and OpenID Connect are, every step of a real login (authorization code + PKCE), the three
tokens a login produces, machine-to-machine tokens, refresh and logout, and how a Spring Boot API trusts a real
identity provider. Everything ran against a real **Keycloak**, the open-source identity provider Job Finder will
use for local runs and tests.

Verified with Keycloak **26.8.1** (in Docker; 26.8.2 was 23 days old, under the few-weeks rule) and the scratch
API from note 42 (Spring Boot 4.2.0, Spring Security 7.2.0). Status: 🧪 verified in a demo; **not in the project
yet** (see [In this project](#in-this-project)).

---

## 1. The problem OAuth solves

Before OAuth, if a job-board app wanted to read your calendar, you gave it your **calendar password**. The app
could then do everything you can, forever, and the only way to stop it was to change your password.

OAuth 2.0 (RFC 6749) replaces that with **delegation**: you log in at the provider (never in the app), approve
*what* the app may do, and the app receives an **access token** that is limited (scopes, audience), short-lived,
and revocable. The app never sees your password.

| Term | Meaning | Job Finder example |
|------|---------|--------------------|
| Resource owner | the user | Alice, an employer |
| **Client** | an application acting for the user (or for itself) | the web frontend, a reporting service |
| **Authorization server** | logs users in, issues tokens | Keycloak |
| **Resource server** | an API that accepts tokens | the Job Finder API |
| Access token | "the bearer may do X at API Y until time T" | a JWT sent as `Authorization: Bearer …` |
| Refresh token | gets new access tokens without logging in again | kept by the client, sent only to Keycloak |
| Scope | what the client asked to do | `openid profile email` |

**Public vs confidential clients.** Code running in a browser or on a phone can't keep a secret (anyone can read
it), so it's a **public** client with no secret. A server-side service can keep one, so it's **confidential**
and authenticates to the authorization server with a secret (or a key).

---

## 2. OpenID Connect: OAuth plus login

OAuth only answers *"may this app call this API?"*. It says nothing about **who the user is**. **OpenID Connect**
(OIDC) is a layer on top of OAuth 2.0 that adds:

- an **ID token**: a JWT that tells the *client* who logged in, when and how;
- a **`userinfo`** endpoint returning profile claims;
- **discovery**: one URL describing every endpoint and key (§4);
- standard scopes (`openid`, `profile`, `email`) and claims (`name`, `email`, `preferred_username`).

Asking for the `openid` scope is what turns an OAuth request into an OIDC login.

| | ID token | Access token |
|-|----------|--------------|
| Standard | OpenID Connect | OAuth 2.0 |
| For | **the client** (who logged in?) | **the API** (may this request proceed?) |
| `aud` | the client (`job-finder-web`) | the API (`job-finder-api`) |
| Send it to an API? | **never** | yes, as `Authorization: Bearer` |

---

## 3. Keycloak

Keycloak is an open-source identity provider (Red Hat / CNCF). Its vocabulary:

| Keycloak | Meaning |
|----------|---------|
| **Realm** | an isolated set of users, clients, roles and keys (its own issuer URL) |
| **Client** | an application registered in the realm: public or confidential, which flows it may use, allowed redirect URIs |
| User | a person; has credentials, attributes, roles |
| Realm role | a role across the realm (`EMPLOYER`, `JOB_SEEKER`, `ADMIN`) |
| Client scope / protocol mapper | what goes into tokens (e.g. an audience mapper adds an `aud` value) |
| Service account | the "user" behind a confidential client's own tokens |

The demo realm, imported from a JSON file at startup so it's reproducible:

| Client | Type | Allowed | Purpose |
|--------|------|---------|---------|
| `job-finder-web` | public | authorization code, **PKCE S256 required**, redirect `http://localhost:3000/callback`; implicit and password grants **off** | the frontend |
| `job-finder-api` | confidential, no flows | only introspection | the API's identity (the `aud` value) |
| `reporting-service` | confidential | client credentials | machine to machine |
| `payments-web` | public | authorization code | another app in the same realm |

`job-finder-web` and `reporting-service` have an **audience mapper** that adds `job-finder-api` to their access
tokens' `aud`. Users: `alice` (`EMPLOYER`) and `carol` (`JOB_SEEKER`).

```bash
docker run -d --name kc-demo -p 127.0.0.1:8180:8080 \
  -e KC_HOSTNAME=http://localhost:8180 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin-demo-only \
  -v "$PWD/import:/opt/keycloak/data/import:ro" \
  quay.io/keycloak/keycloak:26.8.1 start-dev --import-realm
```

```text
INFO  [org.keycloak.exportimport.util.ImportUtils] (main) Realm 'jobfinder' imported
INFO  [io.quarkus] (main) Keycloak 26.8.1 on JVM (powered by Quarkus 3.40.1) started in 15.428s. Listening on: http://0.0.0.0:8080
```

`start-dev` is development mode: HTTP allowed, an embedded database, caching off. Production uses `start` with
HTTPS, a real database and a fixed hostname.

---

## 4. Discovery and the public keys (verified)

Every client and API needs only **one URL**, the issuer; the rest is in its discovery document
(`/.well-known/openid-configuration`, 56 entries):

```text
issuer                   http://localhost:8180/realms/jobfinder
authorization_endpoint   http://localhost:8180/realms/jobfinder/protocol/openid-connect/auth
token_endpoint           http://localhost:8180/realms/jobfinder/protocol/openid-connect/token
userinfo_endpoint        http://localhost:8180/realms/jobfinder/protocol/openid-connect/userinfo
jwks_uri                 http://localhost:8180/realms/jobfinder/protocol/openid-connect/certs
end_session_endpoint     http://localhost:8180/realms/jobfinder/protocol/openid-connect/logout
introspection_endpoint   http://localhost:8180/realms/jobfinder/protocol/openid-connect/token/introspect
revocation_endpoint      http://localhost:8180/realms/jobfinder/protocol/openid-connect/revoke
code_challenge_methods_supported ['plain', 'S256']
```

The **JWKS** (JSON Web Key Set) at `jwks_uri` holds the realm's **public** keys. The API verifies signatures with
them; the private keys never leave Keycloak:

```text
{'kid': 'YQ6i_c-it4ndJ-msd3fzniER...', 'kty': 'RSA', 'alg': 'RS256', 'use': 'sig', 'n': '5OCqfGZHPFH-d4apuy7LN9cF...', 'e': 'AQAB', 'x5c': [...]}
{'kid': 'fLBVRuQEPi-1Mbpoy9AysNqS...', 'kty': 'RSA', 'alg': 'RSA-OAEP', 'use': 'enc', ...}
```

`kid` (key id) is the same value as the `kid` in every token's header, so the API knows which key to use.

---

## 5. The login: authorization code + PKCE, step by step (verified)

```text
 Browser / web app                          Keycloak                                API
 1. make code_verifier (random), code_challenge = BASE64URL(SHA256(verifier)), state, nonce
 2. redirect ── GET /auth?response_type=code&client_id=job-finder-web&redirect_uri=…
                  &scope=openid profile email&state=…&nonce=…&code_challenge=…&code_challenge_method=S256 ──▶
 3. user types the password on KEYCLOAK's page ───────────────────────────▶ checks it
    ◀── 302 Location: http://localhost:3000/callback?state=…&code=<one-time code>
    app checks state == what it sent
 4. POST /token  grant_type=authorization_code, code, redirect_uri, client_id, code_verifier ──▶
    ◀── { access_token, id_token, refresh_token, expires_in: 300 }   Keycloak checks SHA256(verifier) == challenge
 5. GET /api/... Authorization: Bearer <access_token> ──────────────────────────────────────────────▶ validates
```

A script played browser and app (stdlib Python: cookie jar, no redirects followed). Real output:

```text
1. app makes code_verifier (43 chars) and code_challenge = BASE64URL(SHA256(verifier)) = V80zhlCPSAmroD62QY1W...
2. browser -> http://localhost:8180/realms/jobfinder/protocol/openid-connect/auth?response_type=code&client_id=job-finder-web&...  => Keycloak's login page (the app never sees the password)
3. user logs in on Keycloak => 302 Location: http://localhost:3000/callback?state=...&code=182e4805-6839-7d...
   app checks state: True
4. app -> POST /token (grant_type=authorization_code, code, code_verifier) => 200: ['access_token', 'expires_in', 'id_token', 'not-before-policy', 'refresh_expires_in', 'refresh_token', 'scope', 'session_state', 'token_type']
   token_type=Bearer expires_in=300 refresh_expires_in=1800 scope='openid profile email'
```

Why each piece exists:

- **The code, not the token, goes through the browser.** URLs end up in browser history, logs and `Referer`
  headers; a one-time code that is useless on its own is safer there than a token.
- **PKCE** (Proof Key for Code Exchange, RFC 7636): the app keeps the `code_verifier` in memory and sends only its
  hash through the browser. Someone who steals the code (a malicious app registered for the same redirect, a log)
  can't exchange it without the verifier. Required for public clients; recommended for all.
- **`state`**: a random value the app checks on the callback, so an attacker can't make your browser complete
  *their* login (CSRF on the callback).
- **`nonce`**: a random value Keycloak copies into the ID token (verified: `nonce sent == nonce in id_token: True`),
  so an ID token can't be replayed into another login.

### Everything that should fail, does (verified)

| Attempt | Keycloak's answer |
|---------|-------------------|
| Exchange a stolen code **without** the verifier | `400 invalid_grant: PKCE code verifier not specified` |
| Exchange it with a **guessed** verifier | `400 invalid_grant: PKCE verification failed: Code mismatch` |
| Start a login **without** `code_challenge` | redirect with `error=invalid_request&error_description=Missing parameter: code_challenge_method` |
| `redirect_uri=https://evil.example/steal` (not registered) | `400` page: `Invalid parameter: redirect_uri` (the code never leaves Keycloak) |
| Implicit flow (`response_type=token`, token in the URL) | `unauthorized_client: … Implicit flow is disabled for the client.` |
| Password grant (the app collects the password) | `unauthorized_client: Client not allowed for direct access grants` |
| Use the same code twice | `400 invalid_grant: Code not valid` |

⚠️ **A replayed code also kills the tokens it already produced** (verified). After the second exchange attempt,
the legitimate access token was revoked: introspection `active=False`, `userinfo` `401 user_session_not_found`,
while a code used once gives `active=True`, `userinfo` `200`. That is RFC 6749 §4.1.2 ("SHOULD revoke all tokens
previously issued based on that authorization code"): a replayed code means someone stole it, so everything
from it is distrusted. (It surprised the test script first: its "try the code again" step was killing its own
tokens.)

The implicit flow and the password grant are both obsolete: forbidden by the OAuth security best current practice
(RFC 9700) and dropped from the OAuth 2.1 draft. Keycloak's discovery still lists them (`grant_types_supported`
includes `password`, `implicit`): the realm supports them, each **client** must not.

---

## 6. The three tokens of one login (verified)

Decoded from alice's login:

```json
// id_token (RS256, 1131 chars): for the web app
{"iss": "http://localhost:8180/realms/jobfinder", "aud": "job-finder-web", "typ": "ID",
 "sub": "02c1d1d2-1953-49dd-bd5b-3ca39d3d7fca", "azp": "job-finder-web", "nonce": "viWheCn8Od6OSuIZ",
 "auth_time": 1795840928, "exp": 1795841228, "at_hash": "eS3NyLnMrYNV90TAHglNhQ",
 "name": "Alice Employer", "preferred_username": "alice", "email": "alice@example.test", "email_verified": true, ...}

// access_token (RS256, 1214 chars): for the API
{"iss": "http://localhost:8180/realms/jobfinder", "aud": "job-finder-api", "typ": "Bearer",
 "sub": "02c1d1d2-1953-49dd-bd5b-3ca39d3d7fca", "azp": "job-finder-web",
 "realm_access": {"roles": ["EMPLOYER"]}, "scope": "openid profile email",
 "allowed-origins": ["http://localhost:3000"], "exp": 1795841228, "iat": 1795840928, ...}

// refresh_token (HS512, 713 chars): for Keycloak only
{"typ": "Refresh", "aud": "http://localhost:8180/realms/jobfinder", "azp": "job-finder-web", "exp": 1795842728, ...}
```

What to notice:

- **`sub` is a UUID**, not the username. Usernames and emails can change; `sub` never does. Store `sub` as the
  owner of a job, never `preferred_username`.
- `azp` (authorized party) is the client that asked for the token; `aud` is who it's for.
- The roles are **nested** (`realm_access.roles`): no Spring default reads them (§8).
- The access token lives **300 s** (`exp - iat`), the refresh token 1800 s.
- The refresh token is signed with **HS512**, a secret only Keycloak has: to everyone else it's opaque, which is
  correct (only Keycloak ever reads it).

`userinfo` with the access token returns the profile:

```json
{"sub":"02c1d1d2-1953-49dd-bd5b-3ca39d3d7fca","email_verified":true,"name":"Alice Employer","preferred_username":"alice","given_name":"Alice","family_name":"Employer","email":"alice@example.test"}
```

---

## 7. Machine to machine: client credentials (verified)

A service with no user (a nightly reporting job) authenticates as itself:

```bash
curl -s $TOKEN_ENDPOINT -u reporting-service:<secret> -d grant_type=client_credentials
```

```text
['access_token', 'expires_in', 'not-before-policy', 'refresh_expires_in', 'scope', 'token_type'] expires_in 300
{"aud": ["job-finder-api", "account"], "sub": "6cf71791-2aa9-4a74-8717-0ea7f9777386", "azp": "reporting-service",
 "preferred_username": "service-account-reporting-service", "client_id": "reporting-service",
 "realm_access": {"roles": ["default-roles-jobfinder", "offline_access", "uma_authorization"]}, "scope": "profile email"}
```

- No refresh token and no ID token: the service just asks again.
- The token's `sub` is the service account. It has none of the business roles, so authorization rules must
  allow services explicitly (a `PUT` with it → `403`, §8).
- Wrong secret: `unauthorized_client: Invalid client or Invalid client credentials`. A public client asking for
  this: `Public client not allowed to retrieve service account`.

---

## 8. The API trusts Keycloak (verified)

No `JwtDecoder` bean, just properties:

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/jobfinder
spring.security.oauth2.resourceserver.jwt.audiences=job-finder-api
```

and a converter for Keycloak's nested roles:

```java
converter.setJwtGrantedAuthoritiesConverter(jwt -> {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    Collection<String> roles = realmAccess == null ? List.of() : (Collection<String>) realmAccess.getOrDefault("roles", List.of());
    return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList();
});
```

Boot fetched the discovery document and then the JWKS **on the first request**, not at startup (DEBUG log, request
thread):

```text
[nio-8091-exec-3] ...RestTemplateWithDefaultTimeouts : HTTP GET http://localhost:8180/realms/jobfinder/.well-known/openid-configuration
[nio-8091-exec-3] ...RestTemplateWithDefaultTimeouts : HTTP GET http://localhost:8180/realms/jobfinder/protocol/openid-connect/certs
```

Results (rules: `PUT /api/**` needs role `EMPLOYER`):

| Token | `issuer-uri` only | + `audiences=job-finder-api` |
|-------|-------------------|------------------------------|
| alice via `job-finder-web` | `200`, authorities `FACTOR_BEARER, ROLE_EMPLOYER`, name = the `sub` UUID | `200` |
| carol (`JOB_SEEKER`), `PUT` | `403 insufficient_scope` | `403` |
| ⚠️ alice via **`payments-web`** (no `aud` at all), `PUT` | **`200`**: same realm, same signature, same `EMPLOYER` role | `401 The aud claim is not valid` |
| alice's **ID token** sent as a bearer token | not tested | `401 The aud claim is not valid` |
| `reporting-service`, `GET /api/me` / `PUT` | | `200` / `403` |

Every app in a realm is signed by the same keys, so **the issuer alone doesn't say the token is for you**. The
audience does. This confirms note 42's `withPublicKey` finding, now with a real provider and Boot's properties.

### ⚠️ The issuer depends on the URL used to reach Keycloak (verified)

Without `KC_HOSTNAME`, a token requested via `http://127.0.0.1:8180` carries
`"iss": "http://127.0.0.1:8180/realms/jobfinder"`, and the API (configured with `localhost`) rejects it:
`401 The iss claim is not valid`. In docker-compose this is the classic failure: the browser reaches Keycloak at
`localhost:8180`, the API container at `keycloak:8080`, so the tokens never match. Fixed with a fixed public
hostname (verified, `KC_HOSTNAME=http://localhost:8180`): tokens via either URL carry the `localhost` issuer and
the API accepts both.

### Key rotation (verified)

Keycloak was re-created mid-session, which generated a new signing key (`kid` `YQ6i…` → `Fun8…`). The API, still
running with the old key cached, accepted tokens signed with the new one without a restart: on an unknown `kid`,
Spring's JWKS source fetches the key set again. Rotating keys at the provider needs no API deployment.

---

## 9. Refresh, logout and revocation (verified)

```text
== refresh
['access_token', 'expires_in', 'id_token', ..., 'refresh_token', ...]
new access token differs: yes; new refresh token differs: yes
== the OLD refresh token, used again
ok, new tokens                      ← by default an old refresh token keeps working
== logout (end the Keycloak session)
204
== after logout
refresh: {"error":"invalid_grant","error_description":"Session not active"}
introspection of access token A2: active=False
our API with access token A2:     200   (A1: 200)
A2 still accepted by the API for 293 more seconds (exp - now), plus 60 s clock skew
```

- **Logout ends the session at Keycloak** (refresh fails, introspection says inactive), **but the API still accepts
  the access token** until it expires: it validates locally and asks no one (note 42 §12). That's the price of
  stateless validation; the 5-minute lifetime is what bounds it.
- **Refresh token rotation with reuse detection** (realm settings `revokeRefreshToken=true`,
  `refreshTokenMaxReuse=0`), verified:

```text
1st use of R1: new refresh token R2
2nd use of R1 (stolen copy): {"error":"invalid_grant","error_description":"Maximum allowed refresh token reuse exceeded"}
R2 after R1 was replayed:    {'error': 'invalid_grant', 'error_description': "Session doesn't have required client"}
```

  A replayed refresh token means two parties hold it, so Keycloak kills the whole chain: the thief and the user
  both have to log in again, and the thief can't.
- **Introspection** (RFC 7662, `POST /token/introspect` with the API's own client credentials) gives the live
  answer per token, at the cost of a network call per request. Use it only where a revoked token must stop
  *immediately*.

---

## 10. When Keycloak is down (verified)

| Situation | Result |
|-----------|--------|
| API already running (keys cached), Keycloak stopped | **`200`**: validation is local; no call per request |
| **Fresh** API started while Keycloak is down | Starts fine; first authenticated request → ⚠️ **`500`**; public endpoint `200` |
| Keycloak back, same API instance | `200` (it retries; no restart needed) |

The `500` comes from the lazy decoder:

```text
ERROR ... Servlet.service() for servlet [dispatcherServlet] ... threw exception
org.springframework.security.oauth2.jwt.JwtDecoderInitializationException: Failed to lazily resolve the supplied JwtDecoder instance
Caused by: java.lang.IllegalArgumentException: Unable to resolve the Configuration with the provided Issuer of "http://localhost:8180/realms/jobfinder"
Caused by: org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:8180/realms/jobfinder/.well-known/openid-configuration": Connection refused
```

A dependency being down is a `503 Service Unavailable` (retry later), not a `500`, and by ADR 0008 it must be a
Problem Details response logged once. Worth adding in production (not measured here): `jwk-set-uri`, so Boot
needs no discovery call before it can validate, and a readiness check that fails while the keys can't be loaded.

---

## 11. Choosing an identity provider

| Option | Good for | Cost |
|--------|----------|------|
| **Keycloak** (self-hosted) | full control, free, runs locally and in tests (same container), standard OIDC | you operate it: upgrades, database, HA, backups |
| Managed (Auth0, Okta, Amazon Cognito, Microsoft Entra ID) | no operations, MFA/social login built in | per-user pricing, vendor specifics |
| Spring Authorization Server | building a provider *into* a Spring system | you build and operate the login UI, MFA, account management |

The API code is the same for all of them: it's a standards-based resource server configured with an issuer and an
audience. Only the role-claim converter (`realm_access.roles` here) is provider-specific. So the choice can change
later without touching the domain.

**Browser apps and tokens.** A single-page app that holds tokens in JavaScript is exposed to any XSS on the page.
The current OAuth guidance for browser-based apps recommends a **backend-for-frontend (BFF)**: a small server
does the code flow as a confidential client and gives the browser only an `HttpOnly` session cookie (and then
CSRF protection is needed again). That's a frontend decision; the API stays a resource server either way.

---

## In this project

**Not applied yet**: it comes with security, after the `job` module (roadmap). What this study fixes for the ADR:

1. **Keycloak for local runs and tests**: docker-compose for `./mvnw spring-boot:run`, the same image in
   Testcontainers for integration tests, the realm imported from a committed JSON file with **dev-only**
   credentials (never a real secret in the repository), and `KC_HOSTNAME` fixed (§8) so browser and API agree on
   the issuer. Production points at whatever provider is chosen, by configuration only.
2. **The API config**: `issuer-uri` **and** `audiences` (§8), the realm-roles converter, `anyRequest().denyAll()`
   (note 42 §8.3). Properties under `spring.security.oauth2.resourceserver.jwt.*` via environment variables per
   environment.
3. **Owners are `sub` UUIDs** (§6): `Job.ownerId` stores the `sub`, never a username or email.
4. **Clients**: the frontend uses authorization code + PKCE (public client, or a BFF); services use client
   credentials and get explicit rules; implicit and password grants disabled on every client.
5. **Identity provider down = `503` Problem Details** (§10, ADR 0008), not a `500`; `jwk-set-uri` set; readiness
   reflects it.
6. **Short access tokens (5 min) and refresh-token rotation with reuse detection** (§9) in the realm settings.

---

## Run it / verify it

The demo isn't in this repository (Keycloak in Docker, a realm JSON, a Python script, the note 42 scratch API).
The essential commands:

```bash
# Start Keycloak with the realm (§3), then read its discovery document and keys (§4)
curl -s http://localhost:8180/realms/jobfinder/.well-known/openid-configuration
curl -s http://localhost:8180/realms/jobfinder/protocol/openid-connect/certs

# Machine-to-machine token (§7)
curl -s http://localhost:8180/realms/jobfinder/protocol/openid-connect/token \
     -u reporting-service:reporting-secret-demo-only -d grant_type=client_credentials

# A user login needs a browser (or a script that acts like one, §5). In a browser:
#   http://localhost:8180/realms/jobfinder/protocol/openid-connect/auth?response_type=code&client_id=job-finder-web
#   &redirect_uri=http://localhost:3000/callback&scope=openid&state=s1&code_challenge=<S256 hash>&code_challenge_method=S256

# Is this token still valid at Keycloak? (introspection, §9)
curl -s -u job-finder-api:api-secret-demo-only \
     http://localhost:8180/realms/jobfinder/protocol/openid-connect/token/introspect -d token=$ACCESS_TOKEN

# The API (§8)
java -jar api.jar --spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/jobfinder \
                  --spring.security.oauth2.resourceserver.jwt.audiences=job-finder-api
curl -i -H "Authorization: Bearer $ACCESS_TOKEN" localhost:8091/api/me
```

Real outputs are quoted in each section.

---

## Common mistakes

| Mistake | What happens (verified where marked) | Fix |
|---------|--------------------------------------|-----|
| Validating only the issuer | Another app's token in the same realm is accepted, with its roles (verified) | `audiences` + an audience mapper on the clients that call the API |
| Sending the ID token to the API | It's the wrong token (meant for the client); rejected once `aud` is checked (verified) | Send the **access** token; the ID token stays in the client |
| Different Keycloak URLs for browser and API | `401 The iss claim is not valid` (verified) | One public hostname: `KC_HOSTNAME` |
| Owner stored as `preferred_username` / email | Breaks when the user renames; can collide | Store `sub` (a UUID, verified) |
| Roles expected under `scope` | Keycloak's `realm_access.roles` is ignored → `403` | A converter for the nested claim |
| Password grant ("just POST the password") | Disabled on the client: `unauthorized_client` (verified); obsolete (RFC 9700) | Authorization code + PKCE |
| Implicit flow | Disabled: `Implicit flow is disabled for the client` (verified) | Authorization code + PKCE |
| No PKCE on a public client | A stolen code can be exchanged | Require S256 (verified: missing verifier rejected) |
| Not checking `state` on the callback | Login CSRF: the victim ends up in the attacker's account | Random `state`, compared on return |
| Wildcard or loose redirect URIs | Codes delivered to an attacker | Exact redirect URIs (verified: unregistered URI refused) |
| Expecting logout to stop API access at once | Access token still accepted for its lifetime (verified: 293 s left) | Short lifetimes; introspection where it must be immediate |
| Refresh tokens without rotation | A stolen one works as long as the session (verified: reuse accepted by default) | `revokeRefreshToken` + reuse detection (verified) |
| Keycloak down when a fresh API starts | `500` on authenticated requests (verified) | `503` Problem Details, `jwk-set-uri`, readiness |
| Secrets in the committed realm file reused in production | Anyone can mint service tokens | Dev-only values in the repo; real secrets from the environment |
| `Secure` cookies over plain HTTP | Login fails with `cookie_not_found` (verified, scripted client) | HTTPS outside localhost |

---

## Interview questions

**Q: OAuth 2.0 vs OpenID Connect?**
OAuth 2.0 delegates *authorization*: it issues access tokens that let a client call an API. OIDC adds
*authentication* on top: an ID token telling the client who the user is, `userinfo`, discovery and standard claims.

**Q: ID token vs access token?**
The ID token is for the client (`aud` = the client) and says who logged in. The access token is for the API
(`aud` = the API) and authorizes requests. Never send an ID token to an API.

**Q: Walk through the authorization code flow with PKCE.**
The app makes a random verifier and sends its SHA-256 hash with the authorization request; the user logs in at the
provider; the provider redirects back with a one-time code; the app exchanges the code plus the verifier for
tokens. A stolen code is useless without the verifier.

**Q: What are `state` and `nonce` for?**
`state` protects the callback from CSRF (the app checks it matches). `nonce` is copied into the ID token so it
can't be replayed into another login.

**Q: Why are the implicit flow and the password grant deprecated?**
Implicit puts tokens in the URL (history, logs, no refresh, no PKCE). The password grant hands the user's password
to the client, defeating the point of OAuth. Both are excluded from OAuth 2.1.

**Q: How does a service call an API with no user?**
Client credentials: a confidential client authenticates with its own secret and gets an access token for its
service account.

**Q: How does a resource server validate tokens without calling the provider each time?**
It downloads the provider's public keys (JWKS, found via discovery) once, verifies signatures locally, and
re-fetches when it sees an unknown `kid` (key rotation). It checks `iss`, `aud`, `exp`.

**Q: How do you revoke access?**
End the session at the provider (refresh tokens stop working) and keep access tokens short-lived; use
introspection where access must stop immediately. Rotate refresh tokens with reuse detection.

**Q: What happens if a refresh token is used twice with rotation enabled?**
The provider treats it as theft and revokes the whole chain, so both holders must log in again.

**Q: Keycloak vs a managed identity provider vs Spring Authorization Server?**
Keycloak: self-hosted, free, full control, you operate it. Managed: no operations, priced per user. Spring
Authorization Server: a framework to build your own provider. The resource server code is the same for all.
