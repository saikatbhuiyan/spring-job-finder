# 35 — Pagination and Sorting in Spring Boot REST APIs

## Goal

A list endpoint must never return "everything": the table grows, and one request would load it all into
memory, send megabytes, and keep the database busy. **Pagination** returns one page at a time;
**sorting** decides the order. This note covers the REST API side, step by step: how clients ask for a
page, what Spring Data's standard binding does with bad input (measured, and it's surprising), how sorting
can leak data, why pages can repeat rows, and the contract Job Finder uses.

Persistence-side paging (`Page` vs `Slice`, count queries, keyset scrolling) is in
[note 25](25-spring-data-jpa-components.md), [note 26](26-spring-data-interfaces.md) and
[note 28](28-query-annotation.md).

Status: ✅ **applied** ([ADR 0010](../docs/adr/0010-pagination-and-sorting.md)). Boot 4.2.0,
Spring Data 2026.1 (Commons 4.2).

---

## 1. Offset pagination in one picture

```text
GET /api/v1/jobs?page=2&size=10&sort=createdAt,desc

   rows ordered by createdAt desc:   [0 ... 9] [10 ... 19] [20 ... 29] [30 ...
                                       page 0     page 1     page 2  ◄── returned
   offset = page × size = 20

   SQL:  SELECT ... ORDER BY created_at DESC OFFSET 20 ROWS FETCH FIRST 10 ROWS ONLY
         SELECT count(*) ...          ← for totalElements / totalPages
```

Three inputs: **page** (which page, zero-based in Spring), **size** (rows per page), **sort** (the order;
without one, the database may return rows in any order, so "page 2" is meaningless).

## 2. Spring Data's standard approach, and what it does with bad input

The usual tutorial code:

```java
@GetMapping("/jobs")
Page<Job> list(Pageable pageable) {        // bound from ?page=&size=&sort=
    return jobs.findAll(pageable);
}
```

Spring Data's `PageableHandlerMethodArgumentResolver` builds the `Pageable`. A scratch app (Spring MVC +
Spring Data JPA + H2, 7 jobs) measured what it does:

| Request | Result |
|---|---|
| `/jobs` | page 0, size 20, unsorted ✅ |
| `?size=5000` | **size 2000**: silently capped, `200` |
| `?page=-1&size=3` | **page 0**, `200` |
| `?size=0`, `?size=-5`, `?size=abc` | **size 20**: silently the default, `200` |
| `?page=abc&size=3` | **page 0**, `200` |
| `?page=999&size=3` | empty page, `200` (fine) |
| `?sort=nope` | **500** `PropertyReferenceException: No property 'nope' found for type 'Job'` |
| `?sort=title,sideways` | **500**: "sideways" isn't a direction, so it's read as a second property |
| `?sort=company.secretNotes` | **accepted**: `left join company ... order by c1_0.secret_notes` |
| `?page=2147483647&size=100` | **500** `Page offset exceeds Integer.MAX_VALUE` |

Why each is a problem:

- **Silent defaults**: a client with a bug (`size=abc`) gets a valid-looking answer and never finds out.
  Job Finder rejects invalid input everywhere else (ADR 0009); paging must not be the exception.
- **2000 rows per request**: much too large for a public API.
- **500s for client mistakes**: alerts fire for typos, and the client gets no useful message.
- **Sorting by any property** is the worst one (next section).

Returning `Page<Job>` also exposes an entity and `PageImpl`'s JSON, which Spring Data itself says is not a
stable format (ADR 0005, note 30).

## 3. Why sorting needs an allowlist

`?sort=company.secretNotes` worked, although `secretNotes` is never in any response. Three problems:

1. **Information leak.** Sorting reveals the *order* of hidden values. With jobs from companies the attacker
   can see, sorting by a secret column shows which secret is "smaller". Repeated with chosen values, that
   is a binary search on data the client may not read.
2. **Cost.** Every sortable property needs an index, or each request sorts the whole table. Nested paths
   add joins.
3. **Coupling.** Entity property names become part of the API.

Native queries are weaker still. Spring Data puts the property name straight into SQL
(`select * from job order by nope asc`), so any **column** name works. It does reject expressions:
`sort=title desc; drop table job` failed with *"Sort expression ... must only contain property references
or aliases"*, so it's not SQL injection, but it's still "sort by any column".

**Rule: each endpoint lists the properties it can be sorted by.** Everything else is a `400`.

## 4. Why rows repeat or disappear between pages

### Ties

`sort=salary` with several jobs at 5000: the database may return equal rows in **any** order, and the
order can differ between two queries (different plan, parallel scan). Page 1 and page 2 then overlap or miss
rows. The fix is a **tiebreaker**: always end the order with a unique column. Job Finder appends `id`:

```text
sort=salary  →  ORDER BY salary ASC, id ASC     (a total order: no two rows are equal)
```

> Testing note: an in-memory test can't show this problem. Java's sort is *stable* (equal elements keep
> their original order), so removing the tiebreaker didn't break the in-memory response test (mutation
> check). The tiebreaker is guarded by tests on the order that's passed on, not on the rows.

### Drift

Measured: page 0 of `sort=createdAt,desc&size=3` returned jobs 7, 6, 5. Then a new job was inserted. Page 1
returned **5**, 4, 3: job 5 appeared **twice**, because every row moved one position down. A tiebreaker
doesn't help here; it's how offsets work. For search results that's acceptable. Feeds, exports and sync
need **keyset pagination** ("rows after the last one I saw", Spring Data `Window`, note 26).

## 5. Deep pages

`OFFSET 100000` doesn't jump: the database reads 100 000 rows and throws them away, for every request. Deep
pages get slower and slower, and crawlers love them. And as measured, a large `page × size` overflowed
JPA's `int` offset (500).

Job Finder caps the offset: **`page × size ≤ 10 000`** (with size 100, page 100 is the last). Nobody reads
page 5000 of a job search; they refine the search.

## 6. Job Finder's contract

| Parameter | Rule | Default |
|---|---|---|
| `page` | integer ≥ 0 (zero-based) | `0` |
| `size` | integer 1 to 100 | `20` |
| `sort` | `property` or `property,asc` or `property,desc`, repeatable; only properties the endpoint declares | the endpoint's default |
| `page × size` | ≤ 10 000 | |

The order always ends with `id`. A controller declares it like this:

```java
@GetMapping
PagedModel<JobSummary> search(
        @SortableBy(value = {"createdAt", "salary", "title"}, defaultSort = "createdAt,desc") PageQuery page) {
    return new PagedModel<>(jobSearch.search(page.toPageable()).map(JobSummary::from));
}
```

Response (`PagedModel`, Spring Data's stable page DTO):

```json
{
  "content": [ { "id": 1, "title": "Backend engineer", "salary": 7000 }, ... ],
  "page": { "size": 2, "number": 1, "totalElements": 5, "totalPages": 3 }
}
```

Invalid input: **every** problem at once, never a silent default:

```text
GET /api/v1/jobs?page=-1&size=500&sort=secretNotes&sort=title,sideways
```

```json
{
  "type": ".../problems.md#validation-error",
  "status": 400,
  "errors": [
    { "parameter": "page", "code": "Min", "detail": "must be greater than or equal to 0" },
    { "parameter": "size", "code": "Max", "detail": "must be less than or equal to 100" },
    { "parameter": "sort", "code": "InvalidValue", "detail": "is not a sortable property; sortable: createdAt, salary, title" },
    { "parameter": "sort", "code": "InvalidValue", "detail": "must be 'property' or 'property,asc' or 'property,desc'" }
  ]
}
```

The rejected value (`secretNotes`) isn't echoed; the detail lists what *is* allowed instead.

## 7. How it's built

### `PageQuery`: a value that can't be invalid

```java
public record PageQuery(int page, int size, Sort sort) {
    public static final int DEFAULT_SIZE = 20, MAX_SIZE = 100, MAX_OFFSET = 10_000;
    public static final String TIEBREAKER = "id";

    public PageQuery {
        if (page < 0 || size < 1 || size > MAX_SIZE || (long) page * size > MAX_OFFSET) {
            throw new IllegalArgumentException(...);       // nobody can build an invalid one
        }
        if (sort.getOrderFor(TIEBREAKER) == null) {
            sort = sort.and(Sort.by(TIEBREAKER));          // total order, always
        }
    }

    public Pageable toPageable() { return PageRequest.of(page, size, sort); }
}
```

It lives in the `shared` module's **public** package (other modules' controllers use it), next to
`DomainException`. `(long) page * size` avoids an `int` overflow in the check itself.

### A custom argument resolver

Spring MVC fills controller parameters with **`HandlerMethodArgumentResolver`s**: one for `@RequestBody`,
one for `@RequestParam`, and so on. We add one for `PageQuery`:

```java
final class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());   // 1. "is this mine?"
    }

    public PageQuery resolveArgument(MethodParameter parameter, ..., NativeWebRequest request, ...) {
        SortableBy sortable = parameter.getParameterAnnotation(SortableBy.class);  // 2. the allowlist
        List<Violation> violations = new ArrayList<>();
        Integer page = integer(request.getParameter("page"), "page", 0, violations);  // 3. parse, collect
        ...
        if (!violations.isEmpty()) {
            throw new InvalidRequestParametersException(violations);    // 4. all problems at once
        }
        return new PageQuery(page, size, sort);
    }
}
```

Registered through `WebMvcConfigurer.addArgumentResolvers`. Custom resolvers are asked *before* Spring's
catch-all "bind any object from query parameters" resolver, so `PageQuery` is always ours.

`InvalidRequestParametersException` is handled by `GlobalExceptionHandler` like every other validation
error. It's created without a stack trace (`super(message, null, false, false)`): it's control flow for a
client mistake, not a bug.

### Failing at startup, not on the first request

A typo like `@SortableBy(value = "title", defaultSort = "createdAt,desc")` would only blow up when someone
calls the endpoint. A `SmartInitializingSingleton` bean (it runs once all beans exist) walks every
controller method in every `RequestMappingHandlerMapping` and checks each `@SortableBy`. Verified with
`WebApplicationContextRunner`: the context fails with
*"@SortableBy on ...search(PageQuery): default sort 'createdAt,desc' must be 'property[,asc|desc]' with a property from [title]"*.

## 8. Enforcing it

An ArchUnit rule rejects `Pageable` or `Sort` parameters and `Page` / `Slice` return types (also inside
generics like `ResponseEntity<Page<X>>`) on request-mapped methods, so nobody falls back to the tutorial
code.

**Bug found while adding it.** The existing rule "endpoints never expose domain types" matched packages
named `..domain..`, and Spring Data's types live in **`org.springframework.data.domain`**. Its failure
message suddenly read *"RawPagingController.list(Pageable) exposes domain type
org.springframework.data.domain.Pageable"*. Package patterns in architecture rules now only match our own
code (libraries are excluded), for the layer rule too.

## 9. A second bug: query parameters bound to an object

Search filters are often a record bound from query parameters (`@Valid JobFilter filter`, no annotation =
`@ModelAttribute`). Measured before the fix:

```json
{"pointer": "#/size", "code": "Max", ...}                          ← a query parameter as a body pointer
{"pointer": "#/size", "code": "typeMismatch",
 "detail": "Failed to convert value of type 'java.lang.String' to required type 'java.lang.Integer'; For input string: \"abc\""}
```

Now both are `parameter` entries; a value that doesn't convert is `InvalidType` with our message, without
Java type names or the input.

One Spring behaviour to know: if a record's constructor argument can't be converted (`minSalary=abc`), the
record isn't created, so its **constraints aren't checked** in that request. The client sees the conversion
error first, the constraint errors after fixing it.

## 10. Verification summary

| Check | Result |
|-------|--------|
| Scratch app: Spring Data's `Pageable` binding with 16 inputs, SQL inspected | silent defaults, 500s, sort by hidden column, drift reproduced |
| `PaginationTests` (10 HTTP tests): defaults, explicit, all errors at once, non-numbers, offset limit, unsortable endpoint, response shape, page after the last | ✅ |
| `PageQueryTests` (5): invariants, tiebreaker once, startup failures | ✅ |
| `ValidationTests`: query-parameter objects (2 new tests) | ✅ |
| `ArchitectureRulesTests`: paging rule rejects `RawPagingController` (3 violations) | ✅ |
| Mutations: resolver, tiebreaker, offset limit, allowlist, startup check, library packages in the domain rule, the parameter fix | each fails the tests that guard it |
| Full suite `mvn verify` | **115 tests, 0 failures** (was 95) |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Returning an unbounded `List` | Memory and latency grow with the table |
| `Pageable` straight from the request | Invalid input silently becomes defaults; any property is sortable (verified) |
| Default max page size (2000) | Huge responses on request |
| Sorting by any property | Ordering leaks hidden values; unindexed sorts; 500s for typos |
| No tiebreaker | Rows repeat or vanish between pages when sort values are equal |
| Expecting offsets to be stable | Inserts shift rows: duplicates across pages (verified); use keyset for exact paging |
| Unlimited page numbers | Slow deep offsets; overflow 500 (verified) |
| Returning `Page` / `PageImpl` | Unstable JSON, exposes Spring Data internals |
| Testing ordering only in memory | Java's stable sort hides missing tiebreakers |
| Layer patterns like `..domain..` in ArchUnit | Match library packages (`org.springframework.data.domain`) |

## Interview questions

**Q: Offset vs keyset (cursor) pagination?**
Offset (`OFFSET n LIMIT m`) can jump to any page and gives totals, but gets slower for deep pages and
drifts when rows change. Keyset ("rows after this sort key") is fast at any depth and stable, but only goes
to the next/previous page. Search UIs use offset; feeds, sync and exports use keyset.

**Q: `Page` vs `Slice`?**
`Page` runs an extra count query for `totalElements`/`totalPages`. `Slice` only knows whether there's a next
page (it fetches size + 1 rows). Use `Slice` when totals aren't shown.

**Q: Why is a stable sort order important?**
Without a unique tiebreaker, rows with equal sort values can come back in a different order per query, so
consecutive pages overlap or skip rows. Append the primary key.

**Q: Is it safe to bind `Pageable` directly from request parameters?**
Not without limits: Spring Data replaces invalid values with defaults, caps size at 2000, and allows sorting by
any property, including associations and hidden columns. Restrict sortable properties and size.

**Q: How do you add your own controller parameter type in Spring MVC?**
Implement `HandlerMethodArgumentResolver` (`supportsParameter`, `resolveArgument`) and register it with
`WebMvcConfigurer.addArgumentResolvers`.

**Q: How do you prevent deep pagination problems?**
Cap `page × size`, push users to filters, offer keyset pagination for complete traversals, and consider
`Slice` to avoid count queries on large tables.
