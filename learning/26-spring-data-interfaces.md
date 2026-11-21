# 26 — Important Interfaces of Spring Data and Spring Data JPA

## Goal

A precise reference of the repository interfaces you'll extend, and the supporting types (`Pageable`,
`Example`, `Specification`, `Window`…). The hierarchy, method lists and deprecations were **read from
the Spring Data Commons 4.2.0 and Spring Data JPA 4.2.0 jars** (with `javap` and reflection), not from
memory, and the important behaviors were **run** in a scratch app (Boot 4.2.0 / Hibernate 7.4.15 / H2).

Builds on notes 23 and 25. Status: 🧪 verified.

---

## 1. The hierarchy (verified by reflection)

```text
Spring Data Commons (every module: JPA, JDBC, MongoDB, Redis…)
─────────────────────────────────────────────────────────────────
Repository<T, ID>                       marker, no methods
 ├── CrudRepository<T, ID>              basic CRUD (Iterable results)
 │    └── ListCrudRepository<T, ID>     same, List results
 └── PagingAndSortingRepository<T, ID>  findAll(Sort), findAll(Pageable)   ⚠️ NOT a CrudRepository
      └── ListPagingAndSortingRepository<T, ID>

QueryByExampleExecutor<T>               (separate, not a Repository)
 └── ListQueryByExampleExecutor<T>

Spring Data JPA
─────────────────────────────────────────────────────────────────
JpaRepository<T, ID> extends ListCrudRepository, ListPagingAndSortingRepository, QueryByExampleExecutor
JpaSpecificationExecutor<T>             (separate) Specification-based queries, updates, deletes
```

Reflection output:

```text
CrudRepository                     extends [Repository]
ListCrudRepository                 extends [CrudRepository]
PagingAndSortingRepository         extends [Repository]
ListPagingAndSortingRepository     extends [PagingAndSortingRepository]
JpaRepository                      extends [ListCrudRepository, ListPagingAndSortingRepository, QueryByExampleExecutor]
JpaSpecificationExecutor           extends []
```

### ⚠️ Outdated-tutorial trap: `PagingAndSortingRepository` no longer includes CRUD

**Before Spring Data 3.0**, `PagingAndSortingRepository extends CrudRepository`. **Since 3.0 it extends only
`Repository`.** A repository that extends just `PagingAndSortingRepository` has **no** `save` or `findById`.
Verified:

```text
PagingOnlyJobRepository has save()? false, findById()? false, findAll(Pageable)? true
```

To get both, extend both, e.g. `ListCrudRepository<Job, Long>, ListPagingAndSortingRepository<Job, Long>`, or
just `JpaRepository`.

---

## 2. Methods of each interface (from the 4.2.0 jars)

### `CrudRepository<T, ID>`

| Method | Purpose |
|--------|---------|
| `save(S)` / `saveAll(Iterable<S>)` | Insert or update (persist vs merge, note 24 §3) |
| `findById(ID)` → `Optional<T>` | Load one |
| `existsById(ID)` | Existence check |
| `findAll()` / `findAllById(Iterable<ID>)` | Load many |
| `count()` | Count all |
| `deleteById(ID)`, `delete(T)`, `deleteAllById(…)`, `deleteAll(…)`, `deleteAll()` | Delete |

### `ListCrudRepository<T, ID>`

Same methods, but `saveAll`, `findAll` and `findAllById` return **`List<T>`** instead of `Iterable<T>` (added in
Spring Data 3.0). **Prefer the `List*` variants**, which spare you from converting `Iterable`s.

### `PagingAndSortingRepository<T, ID>` / `ListPagingAndSortingRepository`

| Method | Purpose |
|--------|---------|
| `findAll(Sort)` | All rows, sorted |
| `findAll(Pageable)` → `Page<T>` | One page (+ count query) |

### `QueryByExampleExecutor<T>`

`findOne(Example)`, `findAll(Example[, Sort | Pageable])`, `count(Example)`, `exists(Example)`, and
`findBy(Example, fluentQuery -> …)` (§5).

### `JpaRepository<T, ID>`: JPA extras

| Method | Purpose |
|--------|---------|
| `flush()` | Write pending changes to the DB now (still inside the transaction) |
| `saveAndFlush(S)`, `saveAllAndFlush(…)` | Save and flush immediately (e.g. to surface constraint violations early) |
| `deleteAllInBatch(…)`, `deleteAllByIdInBatch(…)`, `deleteAllInBatch()` | One bulk `DELETE` (no entity loading, no callbacks) |
| **`getReferenceById(ID)`** | A lazy **reference** without a SELECT (§6) |
| ~~`getOne(ID)`~~, ~~`getById(ID)`~~, ~~`deleteInBatch(…)`~~ | **`@Deprecated`** (verified by reflection). Use `getReferenceById` / `deleteAllInBatch` |

### `JpaSpecificationExecutor<T>`

| Method | Purpose |
|--------|---------|
| `findOne(Specification)`, `findAll(Specification[, Sort \| Pageable])`, `count(…)`, `exists(…)` | Dynamic queries (note 25 §7) |
| `findBy(Specification, fluent -> …)` | Fluent API: projections, sorting, paging, scrolling |
| **`update(UpdateSpecification)`** | Bulk UPDATE by criteria (**new in Spring Data JPA 4**, §8) |
| **`delete(DeleteSpecification)`** / `delete(PredicateSpecification)` | Bulk DELETE by criteria (**new**) |
| Overloads taking `PredicateSpecification` | The new, simpler predicate type (§8) |

---

## 3. Shaping your own repository API

### `@NoRepositoryBean`: a base interface exposing only what you want (verified)

Extending `JpaRepository` exposes **~40 methods**, including `deleteAll()`. For a module that should
only **read** a table, define your own base:

```java
@NoRepositoryBean                                     // "don't create a bean for this interface itself"
public interface ReadOnlyRepository<T, ID> extends Repository<T, ID> {
    Optional<T> findById(ID id);
    List<T> findAll();
}

public interface CompanyReadRepository extends ReadOnlyRepository<Company, Long> { }
```

```text
has save()? false, has delete()? false, findAll -> [Acme, Globex]
```

Spring Data implements the declared methods by matching their signatures to the built-in implementation.
`@NoRepositoryBean` stops Spring from trying to create a repository for the generic base interface itself.
**Production value:** the interface is the API, so a smaller API leaves fewer ways to misuse it.

### `@RepositoryDefinition`: no inheritance at all (verified)

```java
@RepositoryDefinition(domainClass = Job.class, idClass = Long.class)
public interface JobCounter { long countByStatus(JobStatus status); }
```

```sql
select count(j1_0.id) from job j1_0 where j1_0.status=?      -- countByStatus(OPEN) = 5
```

It's equivalent to extending `Repository<Job, Long>`. Extending the interface is more common; the annotation is
useful when you can't or don't want to extend.

---

## 4. Paging, sorting and scrolling types

| Type | Kind | Role |
|------|------|------|
| `Pageable` / `PageRequest.of(page, size, sort)` | interface / impl | "Give me page 3, 20 per page, sorted by…" |
| `Sort` / `Sort.by("salary").descending()` | class | Ordering |
| `Slice<T>` | interface | Content + `hasNext()` (no count) |
| `Page<T>` | interface **extends `Slice`** | + total elements/pages (count query) |
| `ScrollPosition` | interface | Where to continue: **offset** or **keyset** |
| `Window<T>` | interface | One "window" of results from a scroll + `hasNext()` + `positionAt(i)` |

### Keyset scrolling with `Window` (verified)

Offset paging (`LIMIT 20 OFFSET 100000`) makes the database **read and throw away** 100,000 rows, so deep pages
get slower and slower. **Keyset** scrolling continues **after the last row seen**:

```java
Window<Job> findFirst2ByStatusOrderByIdAsc(JobStatus status, ScrollPosition position);

ScrollPosition pos = ScrollPosition.keyset();          // start
Window<Job> window = jobs.findFirst2ByStatusOrderByIdAsc(OPEN, pos);
pos = window.positionAt(window.size() - 1);            // continue after the last element
```

```sql
-- window 1
select ... from job j1_0 where j1_0.status=? order by j1_0.id fetch first ? rows only
-- window 2, 3: "after id X". No OFFSET at all
select ... from job j1_0 where (j1_0.status=?) and (j1_0.id>?) order by j1_0.id fetch first ? rows only
```

```text
window 1: [Java Developer, Senior Java Engineer] hasNext=true
window 2: [Python Developer, Go Developer] hasNext=true
window 3: [Kotlin Developer] hasNext=false
```

| | Offset (`Page`/`Slice`) | Keyset (`Window`) |
|-|-------------------------|-------------------|
| Jump to page N | ✅ | ❌ (only next/previous) |
| Speed on deep pages | Slower and slower | **Constant** (uses the index) |
| Stable when rows are inserted while scrolling | ❌ rows can shift or duplicate | ✅ |
| Fits | Admin tables with page numbers | **Infinite scroll, feeds, APIs** (Job Finder's job list) |

Keyset needs a **unique, stable sort** (e.g. `id`, or `createdAt` + `id`).

---

## 5. Query by Example (`Example`, `ExampleMatcher`)

Build a **probe** object with the fields to match:

```java
Job probe = new Job("developer", 0, null);
ExampleMatcher matcher = ExampleMatcher.matching().withMatcher("title", contains().ignoreCase());
jobs.findAll(Example.of(probe, matcher));
```

### ⚠️ Trap: primitive fields are always part of the example (verified)

```sql
... where j1_0.status=? and j1_0.salary=? and j1_0.version=? and lower(j1_0.title) like ?
```

```text
naive matcher -> 0 results  <- primitive salary=0 and version=0 were added to the WHERE!
```

Query by Example ignores **`null`** fields, but a primitive `int`/`long` is **never null**. It's `0`, so it was
matched (`salary = 0`, `version = 0`). The `status` field's default value (`OPEN`) was also included. Fix:

```java
matcher.withIgnorePaths("salary", "version", "status")
```

```text
ignore primitives -> [Java Developer, Python Developer, Go Developer, Kotlin Developer]
```

Query by Example is handy for simple "form filters" over strings. For anything with ranges (`salary >= X`),
joins or OR logic, use **Specifications**, which Job Finder's search will use.

---

## 6. `getReferenceById`: a reference without a query (verified)

```java
Job ref = jobs.getReferenceById(1L);     // no SQL
ref.getClass()                           // Job$HibernateProxy
ref.getTitle()                           // NOW: select ... where j1_0.id=?
jobs.getReferenceById(999L).getTitle()   // EntityNotFoundException, only when a field is accessed
```

**Use case:** setting a relation without loading the related row, e.g. `application.setJob(jobs.getReferenceById(jobId))`
when saving a job application. Use `findById` when you need the data, or need to check that the row exists.

---

## 7. `Specification` and the new Spring Data JPA 4 types

| Type | Shape | Use |
|------|-------|-----|
| `Specification<T>` | `toPredicate(Root, CriteriaQuery, CriteriaBuilder)` | Queries (note 25 §7) |
| **`PredicateSpecification<T>`** (new) | `toPredicate(From, CriteriaBuilder)` | A plain condition, reusable in queries, updates **and** deletes |
| **`UpdateSpecification<T>`** (new) | `update((root, update, cb) -> update.set(…)).where(…)` | Bulk update by criteria |
| **`DeleteSpecification<T>`** (new) | `DeleteSpecification.where(…)` | Bulk delete by criteria |

All of them compose with `and`, `or`, `not`, `allOf`, `anyOf` (verified in the jar's API).

### Verified bulk update and delete by criteria

```java
PredicateSpecification<Job> lowPaid = (from, cb) -> cb.lessThan(from.get("salary"), 70000);

jobs.update(UpdateSpecification.<Job>update((root, update, cb) -> update.set("status", JobStatus.CLOSED))
                               .where(lowPaid));
jobs.delete(DeleteSpecification.where((PredicateSpecification<Job>) (from, cb) ->
                               cb.equal(from.get("status"), JobStatus.CLOSED)));
```

```sql
update job j1_0 set status=? where j1_0.salary<?          -- updated 1 row(s)
delete from job j1_0 where j1_0.status=?                  -- deleted 1 row(s); remaining 4
```

These are type-safe alternatives to `@Modifying @Query` strings. The same caveats apply as in note 25 §3: they
bypass the persistence context, callbacks, auditing and `@Version`, and need a transaction.

---

## 8. Which interface should Job Finder's repositories extend? (🔜)

| Repository | Extend | Why |
|------------|--------|-----|
| `JobRepository` (writes + search) | `ListCrudRepository<Job, Long>` + `JpaSpecificationExecutor<Job>` | CRUD + dynamic search, and **not** the whole `JpaRepository` surface (`deleteAllInBatch()`…) |
| Read-only lookups in other modules | A `@NoRepositoryBean` `ReadOnlyRepository` | Can't write by accident |
| Job feed / infinite scroll | Query methods returning **`Window<…>`** projections | Keyset scrolling, fast at any depth |

Rules of thumb:
1. **Extend the smallest interface** that gives what the module needs.
2. Prefer **`List*`** variants over `Iterable` ones.
3. Use **`getReferenceById`**, never the deprecated `getOne` / `getById`.
4. Remember that **`PagingAndSortingRepository` alone has no CRUD** (Spring Data 3+).

---

## Common mistakes

| Mistake | Result (verified where marked) |
|---------|--------------------------------|
| Extending only `PagingAndSortingRepository` and calling `save` | Doesn't compile in Spring Data 3+ (verified: no `save`) |
| Using deprecated `getOne` / `getById` | Deprecation warnings; use `getReferenceById` (verified `@Deprecated`) |
| Query by Example with primitive fields | Unexpected `= 0` conditions, 0 results (verified) |
| Extending `JpaRepository` everywhere | Exposes bulk deletes and more to every caller |
| Offset paging for infinite scroll on big tables | Slower and slower deep pages; use keyset `Window` (verified `id > ?`) |
| Using a `getReferenceById` proxy outside a transaction | `LazyInitializationException` on field access |
| Forgetting `@NoRepositoryBean` on a generic base repository | Startup failure: Spring tries to create a repository for it |

## Interview questions

**Q: Explain the Spring Data repository hierarchy.**
`Repository` (marker) → `CrudRepository` → `ListCrudRepository`. Separately, `PagingAndSortingRepository` →
`ListPagingAndSortingRepository`. `JpaRepository` combines `ListCrudRepository`, `ListPagingAndSortingRepository` and
`QueryByExampleExecutor`, and adds JPA methods like `flush` and `getReferenceById`.

**Q: Does `PagingAndSortingRepository` extend `CrudRepository`?**
Not since Spring Data 3.0. It only extends `Repository`.

**Q: `CrudRepository` vs `ListCrudRepository`?**
Same operations. `ListCrudRepository` returns `List` instead of `Iterable`.

**Q: What does `@NoRepositoryBean` do?**
It marks an intermediate base repository interface so Spring Data doesn't create a bean for it, which lets you
define a custom, restricted base API.

**Q: `getReferenceById` vs `findById`?**
`getReferenceById` returns a lazy proxy without querying (it fails later if the row doesn't exist). `findById`
queries immediately and returns `Optional`.

**Q: Offset paging vs keyset scrolling?**
Offset skips N rows (slower on deep pages, unstable with concurrent inserts). Keyset continues after the last seen key
(`where id > ?`), which is constant-time and stable. Spring Data exposes keyset scrolling with `ScrollPosition` and `Window`.

**Q: When would you use Query by Example vs Specifications?**
QBE for simple equality or string matching on a probe object. Specifications for ranges, joins, OR logic and complex
dynamic queries.
