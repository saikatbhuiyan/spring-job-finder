# Architecture Decision Records

Significant technical decisions are recorded here: the context, the decision, and the trade-offs.
A new decision gets the next number. A changed decision gets a new ADR that supersedes the old one
(old ADRs are never rewritten).

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-modular-clean-architecture.md) | Modular monolith with clean layers inside each module | Accepted |
| [0002](0002-api-versioning-via-uri-path.md) | API versioning via the URI path (`/api/v1`) | Accepted |
| [0003](0003-problem-details-for-errors.md) | RFC 9457 Problem Details for all API errors | Accepted (extended by 0006) |
| [0004](0004-logging-per-environment.md) | Human-readable logs in dev, ECS JSON logs in prod | Accepted |
| [0005](0005-dtos-at-the-api-boundary.md) | DTOs at the API boundary; endpoints never expose domain types | Accepted |
| [0006](0006-global-exception-handling.md) | Global exception handling: `@RestControllerAdvice` + error controller, field-level validation errors, sealed domain exceptions | Accepted (extended by 0008) |
| [0007](0007-cors-policy.md) | CORS: per-environment origin allowlist (default none), validated at startup, applied by a filter to `/api/**` | Accepted |
| [0008](0008-exception-handling-hardening.md) | Exception handling hardening: one 5xx policy for every handler, quiet client disconnects, exactly one `@ControllerAdvice`, fixed English locale | Accepted |
