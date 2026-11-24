# 36 — Filtering and Search in Spring Boot REST APIs

## Goal

"Show me remote Java jobs paying at least 5000, posted this month" is a **filter**. "Find jobs about
*spring microservices*" is a **search**. This note builds both, step by step, from the HTTP request down
to the SQL, and shows what goes wrong with the obvious implementations. Everything was measured: the API
side in Job Finder, the database side against **PostgreSQL 16** (in Docker) with 50 000 rows, because
search performance depends entirely on the database.

Status: ✅ API side **applied**; database side **decided** for the first module with a database
([ADR 0011](../docs/adr/0011-filtering-and-search.md)). Builds on paging ([note 35](35-pagination-and-sorting.md))
and validation ([note 34](34-validation.md)).

---

## 1. The request

```text
GET /api/v1/jobs?q=spring microservices&minSalary=5000&location=DHAKA&location=REMOTE
                &status=OPEN&postedAfter=2026-11-01&page=0&size=20&sort=createdAt,desc
```

| Kind | Example | Meaning |
|---|---|---|
| exact match | `status=OPEN` | equals |
| range | `minSalary=5000`, `maxSalary=9000` | `>=`, `<=` |
| multiple values | `location=DHAKA&location=REMOTE` | any of (`IN`) |
| date | `postedAfter=2026-11-01` | `>=` |
| free text | `q=spring microservices` | full-text search |

Every filter is **optional**: absent means "don't filter by this".

## 2. Binding filters: a record

```java
record JobFilter(@Size(min = 2, max = 100) String q,
                 @PositiveOrZero Integer minSalary,
                 @Size(max = 10) List<String> location,
                 Status status,
                 LocalDate postedAfter) {
    JobFilter {
        q = SearchText.normalize(q);       // see §6
    }
}

@GetMapping
PagedModel<JobSummary> search(@Valid JobFilter filter, @SortableBy(...) PageQuery page) { ... }
```

A record parameter without `@RequestBody` is bound from **query parameters** by name (an implicit
`@ModelAttribute`). `@Valid` checks it; errors are `parameter` entries (note 35 §9 fixed that). Wrapper
types (`Integer`), so absent means `null`.

## 3. Trap: unknown parameters are ignored

Measured: `?titel=java` (typo) answered **200 with every job**. Spring MVC simply ignores parameters no
one asked for. The user sees "no filter applied" and doesn't know why.

Job Finder rejects them, like unknown JSON properties (ADR 0005):

```json
{ "status": 400, "errors": [{ "parameter": "titel", "code": "UnknownParameter", "detail": "is not a known parameter" }] }
```

### How: a `HandlerInterceptor`

An **interceptor** runs around every controller call (`preHandle` before it). Ours, for `/api/**`:

1. Gets the `HandlerMethod` Spring chose for the request.
2. Works out which parameter names the method accepts, once per method (cached):
   - `@RequestParam(name = "page_size")` → `page_size`;
   - an unannotated simple parameter (`String q`) → `q` (Spring binds it by name);
   - a filter record → its component names (or `@BindParam("min_salary")`);
   - a `PageQuery` → `page`, `size`, `sort`;
   - `@RequestParam Map<String, String>` → accepts anything.
3. Reads the names in the query string and throws the validation error for the rest.

Two bugs found while testing it (both made all filters "unknown"):

- **`@Valid` is an annotation too.** "Unannotated record" must mean "no Spring *binding* annotation"
  (`@RequestBody`, `@PathVariable`, ...), not "no annotation at all".
- **`@BindParam` on a record component** isn't on the component. The annotation only targets parameters,
  so Java keeps it on the record's **constructor parameter** (seen with `javap`), which is where Spring
  reads it.

Side effect, intended: `?page=2` on an endpoint that doesn't page is now an error instead of being
ignored.

## 4. Trap: dates in the client's locale

Measured with Spring's defaults: `postedAfter=2026-01-15` **and** `postedAfter=1/15/26` were both accepted.
`01/02/26` became **2 January**, so a European client meaning 1 February got a silently wrong filter.

```properties
spring.mvc.format.date=iso         # LocalDate: 2026-01-15 only
spring.mvc.format.date-time=iso    # LocalDateTime, and Instant as 2026-01-15T10:00:00Z
```

(`spring.mvc.format.time=iso` isn't supported: the app fails to start with "Unknown pattern letter: i".)

An `Instant` without an offset (`2026-01-15T10:00:00`) is rejected; which time zone would it be?

## 5. Trap: commas in list values

`location=Dhaka, Bangladesh` became **two** values, `Dhaka` and `Bangladesh`: Spring splits a list
parameter on commas. Both `location=A&location=B` and `location=A,B` work. Rule: **list filters take
codes** (`DHAKA`, `REMOTE`), never free text.

## 6. Normalizing search text

An empty search box sends `q=` (measured: an empty string, not `null`), and users type extra spaces.
`SearchText.normalize`:

| Input | Result |
|---|---|
| `null`, `""`, `"   "`, tabs, no-break spaces | `null` (no search) |
| `"  java   developer "` | `"java developer"` |
| `"C++ 100%_Remote"` | unchanged: escaping is the database layer's job |

It runs in the record's compact constructor, so `@Size(min = 2)` checks the *normalized* text. (Normalizing
in a constructor is fine; *throwing* there isn't, note 34 §9.)

---

## 7. Database side: composing optional filters with Specifications

A **`Specification<T>`** is one `WHERE` condition, built with the JPA Criteria API. One per filter, `null`
when the filter is absent:

```java
static Specification<Job> minSalary(Integer min) {
    return min == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("salary"), min);
}

Specification<Job> spec = Stream.of(titleContains(f.q()), minSalary(f.minSalary()), company(f.company()))
        .filter(Objects::nonNull)
        .reduce(Specification.unrestricted(), Specification::and);
jobs.findAll(spec, page.toPageable());              // JpaSpecificationExecutor
```

Measured SQL contains only the filters that were sent:

```sql
select ... from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id
where lower(j1_0.title) like ? escape '\' and j1_0.salary>=? and c1_0.name=?
order by j1_0.id offset ? rows fetch first ? rows only
```

The alternative, one JPQL query with `(:minSalary is null or j.salary >= :minSalary)` for every filter,
gives the database one query shape for all combinations, which it can't optimize for any of them.

## 8. Trap: filtering on a collection breaks paging

"Jobs tagged java **or** remote", with a join (job 1 has both tags), page size 2:

```sql
select ... from job j1_0 join job_tags t1_0 on ... join tag t1_1 on ... where t1_1.name in (?,?)
order by j1_0.id offset ? rows fetch first ? rows only
```

Measured: the SQL returned job 1 **twice** (once per matching tag). Hibernate de-duplicated it, so the page
held **one** job instead of two. Spring Data then saw "page 0 with fewer rows than the page size",
concluded it was the last page, and **skipped the count query**:

```text
JOIN:    content [1]     totalElements 1    ← wrong: 3 jobs match
EXISTS:  content [1, 2]  totalElements 3    ← right
```

The fix is an `EXISTS` subquery: "jobs for which a matching tag exists", one row per job.

```java
(root, query, cb) -> {
    Subquery<Long> sub = query.subquery(Long.class);
    Root<Job> job = sub.correlate(root);
    sub.select(cb.literal(1L)).where(job.join("tags").get("name").in(names));
    return cb.exists(sub);
}
```

## 9. Trap: `%` and `_` in user input

`LIKE` treats `%` (any text) and `_` (any one character) as wildcards. Measured on 50 007 jobs:

| Search | Unescaped | Escaped |
|---|---|---|
| `100%` | 200+ rows (every "Filler role 100…") | the one "100% remote" job |
| `_` | **all 50 007** | the one `Data_Engineer` |

```java
String pattern = "%" + text.toLowerCase(Locale.ROOT)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
cb.like(cb.lower(root.get("title")), pattern, '\\');      // escape character declared
```

## 10. Making text search fast

`EXPLAIN ANALYZE` on PostgreSQL 16, 50 007 rows:

| Query | Index | Plan | Time |
|---|---|---|---|
| `lower(title) LIKE '%java%'` | B-tree on `lower(title)` | **Seq Scan** (50 005 rows removed) | 13.9 ms |
| `lower(title) LIKE 'java%'` | same | **Seq Scan** (default collation can't use it) | 11.7 ms |
| `lower(title) LIKE '%java%'` | **trigram GIN** | Bitmap Index Scan | **0.09 ms** |
| `lower(title) LIKE '%ja%'` | trigram GIN | **Seq Scan** (trigrams need 3+ characters) | 12.5 ms |
| `search @@ websearch_to_tsquery('english', 'engineers')` | **GIN on `tsvector`** | Bitmap Index Scan | **0.05 ms** |

A B-tree index is sorted by the whole value, so it can't help with "contains". A **trigram** index
(`pg_trgm`) splits text into 3-letter pieces (`jav`, `ava`), so "contains java" becomes an index lookup.

```sql
create extension pg_trgm;
create index job_title_trgm on job using gin (lower(title) gin_trgm_ops);
```

## 11. Full-text search: what users expect from a search box

```sql
alter table job add column search tsvector generated always as
    (to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))) stored;
create index job_search_gin on job using gin (search);

select ... from job where search @@ websearch_to_tsquery('english', :q)
order by ts_rank(search, websearch_to_tsquery('english', :q)) desc;
```

- `to_tsvector` turns text into normalized words (**lexemes**): lower case, stop words removed, stemmed
  (`engineers`, `engineering` → `engin`).
- Measured: `engineers` found **6** jobs (title + description). `LIKE '%engineers%'` on the title found **0**
  (the titles say "Engineer").
- `websearch_to_tsquery` understands search-box syntax: `java -frontend` → `'java' & !'frontend'`;
  `"data engineer"` → `'data' <-> 'engin'`, a phrase (matched "Data Engineer role", not "Engineer for data").

**Never `to_tsquery` with user input.** Measured:

| Input | `websearch_to_tsquery` | `to_tsquery` |
|---|---|---|
| `java developer` | `'java' & 'develop'` | **ERROR: syntax error in tsquery** |
| `java &` | `'java'` | **ERROR: no operand in tsquery** |
| `drop table;--` | `'drop' & 'tabl'` | (it's just text, never SQL) |

Limits, measured: stop words alone (`the`) match nothing; `eng` doesn't match `engineer` (no prefixes by
default); **`c++` becomes `c`**. For a job board, that last one matters: skills are **tags** with exact
filters, not text.

## 12. Where each technique fits

| Need | Technique |
|---|---|
| exact, range, date, enum filters | `Specification` per filter, B-tree indexes on filtered columns |
| "any of these tags/locations" | `EXISTS` subquery |
| search box (`q`) | PostgreSQL full-text search: `tsvector` + GIN + `websearch_to_tsquery`, `ts_rank` |
| "contains" on a short field (autocomplete) | escaped `LIKE` + trigram GIN, minimum 3 characters |
| skills (`C++`, `C#`) | tags, exact match |
| typo tolerance, synonyms, facets at scale | a search engine (OpenSearch/Elasticsearch); not needed now |

## 13. Verification summary

| Check | Result |
|-------|--------|
| API probe: unknown parameter, commas, empty values, dates (default and ISO), enum case | 4 problems found |
| PostgreSQL 16 scratch app (50 007 rows): Specifications, JOIN vs EXISTS paging, LIKE escaping, `EXPLAIN ANALYZE` for 5 query types, full-text matching and syntax | results above |
| `FilteringTests` (15 HTTP tests) and `SearchTextTests` (7) | ✅ |
| Mutations: no interceptor / no ISO settings / no normalization | each fails the tests that guard it |
| Full suite `mvn verify` | **137 tests, 0 failures** (was 115) |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Ignoring unknown query parameters | A typo returns unfiltered results (verified) |
| Locale-dependent date parsing | `01/02/26` means different days to different clients (verified) |
| Free text in list parameters | Commas split values (verified) |
| `(:x is null or ...)` for every optional filter | One generic query shape for all combinations |
| JOIN for collection filters in paged queries | Short pages and a wrong `totalElements` (verified) |
| Unescaped `LIKE` input | `_` matched every row (verified) |
| `LIKE '%x%'` expecting a B-tree index to help | Sequential scan (verified) |
| `to_tsquery(userInput)` | SQL errors for normal input (verified) |
| Full-text search for skill names | `c++` becomes `c` (verified) |
| Testing PostgreSQL search on H2 | H2 has no `tsvector`, `pg_trgm` or the same planner; use Testcontainers |

## Interview questions

**Q: How do you implement optional filters with Spring Data JPA?**
One `Specification` per filter, returning `null` when the filter is absent, combined with `and`, executed
via `JpaSpecificationExecutor.findAll(spec, pageable)`. Alternatives: Querydsl, or the Criteria API directly.

**Q: Why can filtering on a `@ManyToMany` break pagination?**
A join returns one row per matching child, so parent rows repeat; the page has fewer distinct parents and
counts can be wrong. Use an `EXISTS` subquery (or `distinct` with a separate count).

**Q: Why is `LIKE '%term%'` slow, and how do you fix it?**
A leading wildcard can't use a B-tree index, so the database scans every row. In PostgreSQL, a trigram GIN
index (`pg_trgm`) supports it; for word search, use full-text search.

**Q: Full-text search vs `LIKE`?**
Full-text search matches words with stemming and stop words, can rank results, and uses a GIN index. `LIKE`
matches character sequences exactly and is slow on large tables without trigram indexes.

**Q: When would you add Elasticsearch/OpenSearch?**
When requirements exceed what the database does well: typo tolerance, synonyms, faceted search at large
scale, or search across many sources. The cost is a second datastore to keep in sync.

**Q: Should an API reject unknown query parameters?**
For a strict API, yes: it catches typos that would otherwise silently return unfiltered data. The trade-off
is that clients can't add their own parameters (cache busters, tracking).
