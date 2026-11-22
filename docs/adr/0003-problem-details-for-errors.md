# ADR 0003: RFC 9457 Problem Details for all API errors

- **Status:** Accepted; implemented and extended by [ADR 0006](0006-global-exception-handling.md)
- **Date:** 2026-11-20

## Context

Spring Boot's default error body (`{"timestamp","status","error","path"}`) is Spring-specific.
The error format is part of the API contract: changing it after clients depend on it is a
breaking change, so it must be decided before the first endpoint ships.

## Decision

All error responses use **RFC 9457 Problem Details** (`Content-Type: application/problem+json`):

```properties
spring.mvc.problemdetails.enabled=true
```

Domain-specific errors (e.g. "job not found") will be mapped to Problem Details in a global
`@RestControllerAdvice`, adding fields where useful. Exception messages and stack traces are never
exposed to clients.

## Consequences

- A standard format that clients, gateways and API tooling understand.
- Framework errors (malformed JSON, missing parameters, unsupported versions…) are already
  consistent with our own future errors.
- Locked in by `ErrorResponseTests`.

## References

- Learning note: `learning/16-rest-controller.md` §7
