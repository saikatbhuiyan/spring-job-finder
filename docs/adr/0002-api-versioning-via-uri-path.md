# ADR 0002: API versioning via the URI path

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

The API will be consumed by clients we don't control on the same release cycle (web frontend,
possible mobile apps, partner integrations). Breaking changes must be possible without breaking
existing clients. Spring Framework 7 offers built-in API versioning with four resolution
strategies: path segment, query parameter, header, and media type parameter.

## Decision

Version the API in the **URI path**: `/api/v{major}/...` (e.g. `/api/v1/jobs`), using Spring
Framework 7's built-in support:

```properties
spring.mvc.apiversion.use.path-segment=1
```

Controllers map versions with `@GetMapping(version = "1")`. Endpoints that are unchanged across
versions use a baseline (`version = "1+"`). Only **breaking** changes get a new major version.
Retiring a version uses `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) headers via
`StandardApiVersionDeprecationHandler`, with at most two major versions (N and N−1) live at once.

## Consequences

- Visible, easy to document and try, and cache- and gateway-friendly.
- Unsupported versions are rejected with `400` Problem Details (see ADR 0003).
- Locked in by `ApiVersioningTests`, which also proves Actuator endpoints are unaffected.
- Trade-off: the same resource has several URLs (one per version), which is less "pure" REST than
  header or media-type versioning. Accepted for practicality.

## References

- Learning note: `learning/18-api-versioning.md`
