# ADR 0011: Filtering and search

- **Status:** Accepted
- **Date:** 2026-11-24
- **Extends:** [ADR 0009](0009-request-validation.md), [ADR 0010](0010-pagination-and-sorting.md)

## Context

Job search needs filters (salary range, location, status, posting date) and free-text search. Measured
before deciding (`learning/36-filtering-and-search.md`): the API binding in Job Finder (Boot 4.2), and the
persistence side in a scratch app against **PostgreSQL 16** with 50 000 rows.

**API side**

1. **Unknown query parameters are ignored**: `?titel=java` (a typo) returned `200` with **no filter**.
2. **Dates accepted the locale's short format** as well as ISO: `01/02/26` became 2 January, whatever the
   client meant.
3. A list parameter is **split on commas**: `location=Dhaka, Bangladesh` is two values.
4. An empty search box sends `?q=`: an empty string, not "no search".

**Persistence side**

5. Filtering a **collection** with a JOIN breaks paging: for "jobs tagged java or remote" with page size 2,
   the SQL returned job 1 twice, Hibernate de-duplicated it, the page held **one** job, and Spring Data,
   seeing a short first page, skipped the count query: **`totalElements: 1` instead of 3**. An `EXISTS`
   subquery returned the right page and total.
6. **`LIKE` wildcards in user input**: searching `100%` matched 200+ unrelated rows, `_` matched all 50 007.
7. `lower(title) LIKE '%java%'` is a **sequential scan** (14 ms) even with a B-tree index on
   `lower(title)`; `'java%'` too with the default collation. A **trigram GIN** index made it 0.09 ms, but
   only for 3+ characters (`'%ja%'` was a sequential scan again).
8. **Full-text search** (`tsvector` + GIN, `websearch_to_tsquery`): 0.05 ms, and `engineers` found 6 jobs
   by stemming where `LIKE '%engineers%'` found 0. `websearch_to_tsquery` never fails on user input
   (`drop table;--` becomes plain words); `to_tsquery` raised SQL errors even for `java developer`. Stop
   words alone (`the`) match nothing; prefixes (`eng`) don't match; `c++` is parsed as `c`.

## Decision

**API (implemented now, `shared` module)**

1. **Unknown query parameters are a `400`** `validation-error` with code `UnknownParameter`
   (`UnknownQueryParameterInterceptor` on `/api/**`). Known names come from the handler method:
   `@RequestParam` names, unannotated simple parameters, properties of objects bound from query parameters
   (`@BindParam` names respected), and `page`/`size`/`sort` for `PageQuery`. A `@RequestParam Map`
   accepts anything.
2. **ISO-8601 dates only** in query parameters: `spring.mvc.format.date=iso`,
   `spring.mvc.format.date-time=iso` (`2026-01-15`, `2026-01-15T10:00:00Z`).
3. **Filters are a record** bound from query parameters, validated with `@Valid` (all properties optional,
   wrapper types, ranges as `minX`/`maxX`). List filters take **codes**, never free text (commas split them);
   clients repeat the parameter (`location=DHAKA&location=REMOTE`).
4. **Free text is `q`**, normalized with `SearchText.normalize` in the filter's constructor (trimmed,
   whitespace collapsed, blank = no search), then `@Size(min = 2, max = 100)`.

**Persistence (applied with the first module that has a database)**

5. **Optional filters are composed with Spring Data JPA `Specification`s**: one per filter, `null` when
   the filter is absent, combined from `Specification.unrestricted()`. No `(:x is null or ...)` JPQL.
6. **Filters on collections use `EXISTS` subqueries, never joins**, in paged queries.
7. **Free text uses PostgreSQL full-text search**: a generated `tsvector` column with a GIN index and
   `websearch_to_tsquery`, ranked with `ts_rank`. Never `to_tsquery` on user input.
8. **Substring matching** (e.g. company-name autocomplete) uses `LIKE` with escaped wildcards and a trigram
   GIN index, and requires at least 3 characters.
9. **Skills and technologies are exact tag filters**, not text search (`c++`, `c#`, `.net` don't survive the
   text parser).
10. A dedicated search engine (OpenSearch / Elasticsearch) is not used: PostgreSQL covers the requirements
    without a second datastore to keep in sync.

Locked in by `FilteringTests` (15 HTTP tests) and `SearchTextTests` (7). Mutation checks: removing the
interceptor, the ISO settings or the normalization each fails the tests that guard it.

## Consequences

**Positive**
- A typo in a filter is reported, not answered with unfiltered results.
- Dates mean the same thing for every client.
- Search is fast (indexed), forgiving (stemming) and safe (no query syntax errors from user input).
- Paged results stay correct when filtering on collections.

**Negative / accepted trade-offs**
- Clients can't add arbitrary parameters (cache busters such as `_=123`, tracking parameters). They must not
  be sent to the API.
- Full-text search doesn't find word fragments (`eng`); that's what the trigram-indexed fields are for.
- The `tsvector` column and GIN indexes cost write time and storage.
- The interceptor derives names from method signatures; an unusual binding (a custom argument resolver
  reading other parameters) must be added to it.

**Follow-ups**
- With the Job module: Flyway migration for `pg_trgm`, the generated `tsvector` column and GIN indexes;
  Testcontainers tests for the search queries (H2 has none of these features).
- Relevance sorting (`sort=relevance`) when `q` is present.

## References

- PostgreSQL 16 documentation: "Full Text Search", `pg_trgm`
- Spring Data JPA reference: "Specifications"
- Learning note: `learning/36-filtering-and-search.md`
