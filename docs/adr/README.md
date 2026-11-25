# Architecture Decision Records

Significant technical decisions are recorded here: the context, the decision, and the trade-offs.
A new decision gets the next number. A changed decision gets a new ADR that supersedes the old one
(old ADRs are never rewritten).

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-modular-clean-architecture.md) | Modular monolith with clean layers inside each module | Accepted |
| [0002](0002-api-versioning-via-uri-path.md) | API versioning via the URI path (`/api/v1`) | Accepted |
| [0003](0003-problem-details-for-errors.md) | RFC 9457 Problem Details for all API errors | Accepted (extended by 0006, 0013, 0014) |
| [0004](0004-logging-per-environment.md) | Human-readable logs in dev, ECS JSON logs in prod | Accepted (extended by 0013) |
| [0005](0005-dtos-at-the-api-boundary.md) | DTOs at the API boundary; endpoints never expose domain types | Accepted (decisions 7, 8 implemented by 0010, 0009) |
| [0006](0006-global-exception-handling.md) | Global exception handling: `@RestControllerAdvice` + error controller, field-level validation errors, sealed domain exceptions | Accepted (extended by 0008, 0009) |
| [0007](0007-cors-policy.md) | CORS: per-environment origin allowlist (default none), validated at startup, applied by a filter to `/api/**` | Accepted (extended by 0012, 0013, 0014) |
| [0008](0008-exception-handling-hardening.md) | Exception handling hardening: one 5xx policy for every handler, quiet client disconnects, exactly one `@ControllerAdvice`, fixed English locale | Accepted (extended by 0009, 0013) |
| [0009](0009-request-validation.md) | Request validation: violation codes, one format for bodies/parameters/headers, strict JSON types, DTO conventions enforced by ArchUnit | Accepted (extended by 0010, 0011) |
| [0010](0010-pagination-and-sorting.md) | Pagination and sorting: validated `PageQuery` contract, per-endpoint sort allowlist, `id` tiebreaker, offset limit, `PagedModel` responses | Accepted (extended by 0011) |
| [0011](0011-filtering-and-search.md) | Filtering and search: unknown query parameters rejected, ISO dates, filter records, normalized `q`; Specifications, `EXISTS` for collections, PostgreSQL full-text search | Accepted |
| [0012](0012-rate-limiting.md) | Rate limiting: Bucket4j token bucket per client address on `/api/**`, bounded memory, `429` + `Retry-After`, proxy-safe client identity; Redis + fail-open for several instances | Accepted |
| [0013](0013-request-correlation.md) | Request correlation: the W3C trace ID (Micrometer Tracing + OpenTelemetry, no exporter yet) in every log line, the `X-Trace-Id` header and Problem Details; W3C only, no baggage; async and filter failures stay in the trace | Accepted |
| [0014](0014-idempotency-keys.md) | Idempotency keys: `Idempotency-Key` on `POST`/`PATCH` (required with `@Idempotent`), fingerprinted, atomic claim, 2xx replayed for 24 h, `422`/`409`/`400`; in memory now, database store in the business transaction next | Accepted |
