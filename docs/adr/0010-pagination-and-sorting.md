# ADR 0010: Pagination and sorting

- **Status:** Accepted; extended by [ADR 0011](0011-filtering-and-search.md)
- **Date:** 2026-11-24
- **Implements:** [ADR 0005](0005-dtos-at-the-api-boundary.md) decision 7. **Extends:**
  [ADR 0009](0009-request-validation.md)

## Context

List endpoints (job search first) must return results in pages. Spring Data's standard approach is a
`Pageable` controller parameter, bound by `PageableHandlerMethodArgumentResolver`, and a `Page` in the
response. Measured in a scratch app (Boot 4.2, Spring Data 2026.1, H2; `learning/35-pagination-and-sorting.md`):

| Request | Spring Data's binding |
|---|---|
| `size=5000` | silently capped at 2000 rows, `200` |
| `page=-1`, `size=0`, `size=-5`, `size=abc`, `page=abc` | silently replaced by defaults, `200` |
| `sort=nope`, `sort=title,sideways` | `500` (`PropertyReferenceException`; "sideways" is read as a property) |
| `sort=company.secretNotes` | accepted: results ordered by a column the client can't read (a join, and an oracle on its values) |
| `page=2147483647&size=100` | `500` ("Page offset exceeds Integer.MAX_VALUE") |
| sort on a non-unique column, row inserted between requests | the same row on two consecutive pages (offset drift) |

Native queries put the sort property into SQL unchecked against the entity (`order by nope`): Spring
Data blocks expressions (`title desc; drop table job` is rejected), but any column name is accepted.

This contradicts ADR 0009 (invalid input is rejected, never silently changed). `Page`'s own JSON isn't a
stable contract either (ADR 0005 decision 7).

## Decision

1. **The API owns the paging contract**: a `PageQuery` controller parameter (`shared` module API), bound
   and validated by our own resolver from `page` (zero-based, default 0), `size` (1–100, default 20) and
   repeatable `sort=property[,asc|desc]`. Every invalid value is reported at once as a `validation-error`
   (`parameter` entries, codes `InvalidType`, `Min`, `Max`, `InvalidValue`); nothing falls back to a
   default.
2. **Sort allowlist per endpoint**: `@SortableBy({"createdAt", "salary", "title"}, defaultSort = "createdAt,desc")`.
   Without it an endpoint can't be sorted by the client. Declarations are checked at startup (a default
   sort outside the allowlist, or the annotation on another type, fails startup).
3. **Total order**: `id` is always appended as a tiebreaker, so equal sort values can't swap between
   requests.
4. **Offset limit**: `page × size ≤ 10 000`. Deeper offsets get slower with every page (the database reads
   and discards every skipped row) and overflowed JPA's `int` offset. Clients narrow the search instead.
5. **Spring Data types inside**: `PageQuery.toPageable()` gives use cases and repositories a standard
   `Pageable` (allowed in inner layers by ADR 0001). Responses are Spring Data's `PagedModel`
   (`{"content": [...], "page": {"size", "number", "totalElements", "totalPages"}}`), its stable DTO.
6. **Enforced by ArchUnit** (`ENDPOINTS_PAGE_THROUGH_THE_API_CONTRACT`): no `Pageable`/`Sort` parameters and no
   `Page`/`Slice` in return types of request-mapped methods, including generic type arguments.
7. **Dependency**: `spring-data-commons` only. Persistence comes later (roadmap); the contract is in place
   for the first list endpoint.
8. Locked in by `PaginationTests` (10 HTTP tests), `PageQueryTests` (5) and `ArchitectureRulesTests`.
   Mutation checks: removing the resolver, the tiebreaker, the offset limit, the allowlist or the startup
   check each fails the tests that guard it.

Found and fixed along the way:
- `ENDPOINTS_DO_NOT_EXPOSE_DOMAIN_TYPES` and the layer rule matched library packages named like our layers:
  `org.springframework.data.domain.Pageable` was reported as a "domain type". Layer patterns now only match
  our own code.
- A `@Valid` object bound from **query parameters** reported its fields as body pointers (`#/size`), and
  a value that didn't convert as Spring's `typeMismatch` with Java type names and the rejected input. They
  are now `parameter` entries with `InvalidType`.

## Consequences

**Positive**
- Paging input follows the same rules and error format as every other input.
- Clients can only sort by what an endpoint declares, which also keeps sorting to indexed columns.
- Stable pages: no ties in the order, bounded work per request.

**Negative / accepted trade-offs**
- Offset paging still drifts when rows are inserted or deleted between requests (a row can repeat or be
  skipped). Acceptable for search results; feeds or exports that must be exact need keyset pagination
  (Spring Data `Window` / `ScrollPosition`, note 26).
- `Page` runs a `count` query per request. Endpoints that don't need totals can use `Slice` (mapped to
  their own response DTO).
- Sort names are the API's names. If an entity property is named differently, the use case maps it.
- The 10 000 offset limit means no "jump to page 5000"; that's intended.

**Follow-ups**
- When a Spring Data starter adds Spring Data's web auto-configuration, also set
  `spring.data.web.pageable.serialization-mode=via-dto` as a backstop for a `Page` nested inside a DTO
  (the ArchUnit rule only sees method signatures).
- Keyset (`Window`) responses when an endpoint needs exact, drift-free paging.

## References

- Spring Data Commons reference: "Web support", "Scrolling"
- Learning note: `learning/35-pagination-and-sorting.md`
