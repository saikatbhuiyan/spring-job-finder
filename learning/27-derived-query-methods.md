# 27 — Spring Data JPA Derived Query Methods

## Goal

**Derived query methods** let you write a query just by naming a repository method:
`findByCompanyNameAndSalaryGreaterThan(...)`. Spring Data parses the name when the app starts and
generates the query. This note covers the grammar, every common keyword **with the SQL it actually
produced**, return types, and the traps (null parameters, `And`/`Or` precedence, derived deletes).

Verified in a scratch app (Boot 4.2.0, Spring Data JPA 4.2.0, Hibernate 7.4.15, H2) with SQL logging.
Status: 🧪 verified.

---

## 1. Anatomy of a method name

```text
find  Distinct  Top2   By   CompanyName  IgnoreCase  And  SalaryGreaterThan  OrderBy  SalaryDesc
└┬─┘  └──┬───┘  └┬─┘   └┬┘  └────┬────┘  └───┬────┘  └┬┘  └───────┬───────┘  └──┬──┘  └───┬────┘
subject modifier limit  │   property     modifier  connector   property+operator  │     order
                   separator                                                  order by clause
```

| Part | Options |
|------|---------|
| **Subject** | `find…By`, `read…By`, `get…By`, `query…By`, `search…By`, `stream…By` (all the same), `count…By`, `exists…By`, `delete…By` / `remove…By` |
| **Modifiers** | `Distinct`; limits `First`, `Top`, `First5`, `Top10` |
| **Predicates** | `Property` + optional **operator keyword** (§3), joined by `And` / `Or` |
| **Ordering** | `OrderBy` + `Property` + `Asc`/`Desc` (repeatable: `OrderByCompanyNameAscTitleDesc`) |
| **Extra parameters** | `Sort`, `Pageable`, `ScrollPosition`, `Limit`, or a `Class<T>` for dynamic projections |

Parameters are matched to predicates **by position**, in order.

---

## 2. Subjects (verified SQL)

| Method | SQL | Result |
|--------|-----|--------|
| `long countByStatus(OPEN)` | `select count(j1_0.id) from job j1_0 where j1_0.status=?` | `4` |
| `boolean existsByTitleIgnoreCase("JAVA DEVELOPER")` | `select j1_0.id from job j1_0 where upper(j1_0.title)=upper(?) fetch first ? rows only` | `true` |
| `findDistinctByCompanyName("Acme")` | `select distinct … join company c1_0 … where c1_0.name=?` | 2 jobs |
| `findTop2ByOrderBySalaryDesc()` | `… order by j1_0.salary desc fetch first ? rows only` | the 2 best-paid |
| `findFirstByCompanyNameOrderBySalaryAsc("Globex")` | `… where c1_0.name=? order by j1_0.salary fetch first ? rows only` | `Optional[...]` |

Note that `exists…` fetches **one id with a limit**, not a count. That's efficient, so prefer it over
`count…() > 0`.

---

## 3. Predicate keywords (verified SQL)

| Keyword | Example | SQL |
|---------|---------|-----|
| *(none)* / `Is` / `Equals` | `findByTitle(t)` | `title=?` |
| `Not` | `findByStatusNot(OPEN)` | `status<>?` |
| `Between` | `findBySalaryBetween(80000, 100000)` | `salary between ? and ?` |
| `LessThan` / `LessThanEqual` / `GreaterThan` / `GreaterThanEqual` | `findBySalaryGreaterThanEqualAndStatus(85000, OPEN)` | `salary>=? and status=?` |
| `After` / `Before` (dates) | `findByCreatedAtAfter(instant)` | `created_at>?` |
| `IsNull` / `IsNotNull` | `findByDescriptionIsNull()` | `description is null` |
| `True` / `False` | `findByRemoteTrue()` | `remote=true` |
| `In` / `NotIn` | `findByStatusIn(List.of(CLOSED))` | `status in (?)` |
| `Like` / `NotLike` | `findByTitleLike("%dev%")` | `title like ?` (you supply the `%`) |
| `StartingWith` / `EndingWith` / `Containing` | `findByTitleStartingWith("S")` | `title like ? escape '\'` (Spring adds the `%`) |
| `IgnoreCase` / `AllIgnoreCase` | `findByTitleContainingIgnoreCase("java")` | `upper(title) like upper(?) escape '\'` |

### `Containing` escapes wildcards in the input (verified)

```java
findByTitleContainingIgnoreCase("%")      // a user searches for "%"
```

```sql
where upper(j1_0.title) like upper(?) escape '\'
-- → [100% Remote Tester]   (only the title that literally contains "%")
```

Spring Data escapes `%` and `_` in the **value**, so a user can't turn a search into "match everything". With
plain `Like` you pass the pattern yourself and must handle escaping.

---

## 4. Nested properties (relations)

```java
List<Job> findByCompanyNameIgnoreCaseOrderByTitle(String name);    // Job.company.name
```

```sql
select … from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id
where upper(c1_0.name)=upper(?) order by j1_0.title
-- "aCmE" → [Java Developer, Senior Java Engineer]
```

Spring resolves `CompanyName` as `company` → `name` and generates the **join**. If an entity had both a
`companyName` field and a `company` relation, the name would be ambiguous. Use an underscore to force the
split: `findByCompany_Name`.

---

## 5. ⚠️ `And` binds tighter than `Or` (verified)

```java
findByCompanyNameAndSalaryGreaterThanOrRemoteTrue("Acme", 100000)
```

```sql
where c1_0.name=? and j1_0.salary>? or j1_0.remote=true
--     └──────────── (A and B) ──────┘ or C
-- → [Senior Java Engineer (Acme, 120k), Python Developer (Globex, remote)]
```

The SQL is `(A and B) or C`. You **can't** write `A and (B or C)` in a method name. When you need grouping,
use `@Query` or a Specification (note 25 §7).

---

## 6. Return types

| Return type | Behavior (verified where marked) |
|-------------|----------------------------------|
| `List<T>` | Zero or more |
| `Optional<T>` | `findByTitle("Nope")` → `Optional.empty` ✅ |
| `T` (single entity) | `null` when nothing matches |
| single `T`/`Optional<T>` but **several rows match** | **`IncorrectResultSizeDataAccessException`** ✅ |
| `long` / `boolean` | With `count…` / `exists…` |
| `Stream<T>` | Streams rows; needs an **open transaction** and must be **closed** (`try (Stream<Job> s = …)`) ✅ |
| `Page<T>` + `Pageable` | Page + count query ✅ (`total=3`) |
| `Slice<T>` / `Window<T>` | No count / keyset scrolling (note 26 §4) |
| Interface or record projection | Only the needed columns |
| `<T> List<T> findByX(…, Class<T> type)` | **Dynamic projection**: the caller chooses the shape ✅ |

Dynamic projection, verified:

```java
<T> List<T> findByRemote(boolean remote, Class<T> type);
findByRemote(true, TitleOnly.class)          // record TitleOnly(String title)
```

```sql
select j1_0.title from job j1_0 where j1_0.remote=?     -- only the title column
-- [TitleOnly[title=Senior Java Engineer], TitleOnly[title=Python Developer]]
```

### ⚠️ Single result, multiple rows (verified)

After a second "Java Developer" was added, `Optional<Job> findByTitle("Java Developer")` threw
**`IncorrectResultSizeDataAccessException`**. A single-result method is a **claim that the result is unique**. Back
it with a **unique constraint** in the database, or use `findFirstBy…` / return a `List`.

---

## 7. ⚠️ `null` parameters mean `IS NULL`, not "ignore" (verified)

```java
findByDescription(null)
```

```sql
where j1_0.description is null       -- 4 jobs
```

Passing `null` does **not** skip the condition. It becomes `IS NULL`. For **optional search filters** (keyword
may or may not be given), derived queries are the wrong tool. A search like
`findByTitleContainingAndCompanyName(kw, null)` would search for jobs *without* a company. **Use
Specifications** (note 25 §7), where a `null` filter returns no predicate and is skipped. That's why Job Finder's
search will use Specifications.

---

## 8. ⚠️ Derived deletes load every entity first (verified)

```java
@Transactional long deleteByStatus(JobStatus status);
```

```sql
select … from job j1_0 where j1_0.status=?          -- 1 SELECT
delete from job where id=? and version=?            -- then ONE DELETE PER ROW
delete from job where id=? and version=?
delete from job where id=? and version=?
delete from job where id=? and version=?
delete from job where id=? and version=?            -- deleted 5
```

Spring Data **loads** each entity and calls `EntityManager.remove` one at a time. That's correct when you need
lifecycle callbacks, cascades or `@Version` checks, but it's **N+1 deletes**. For bulk removal use one statement:
`@Modifying @Query("delete …")`, `deleteAllInBatch`, or `DeleteSpecification` (note 26 §7).

Derived `delete…` methods need a **transaction** (hence `@Transactional` on the method above, or call them from a
transactional service).

---

## 9. Mistakes are caught at startup (verified)

```java
List<Job> findByTitel(String t);   // typo
```

```text
QueryCreationException: Cannot create query for method …
PropertyReferenceException: No property 'titel' found for type 'Job'; Did you mean 'title'
```

The app **refuses to start**, and Spring suggests the fix. A typo can't reach production.

---

## 10. When to use derived queries, and when not to

| Use derived queries for | Use something else for |
|-------------------------|------------------------|
| Simple, fixed lookups: `findByEmail`, `existsBySlug`, `countByStatus` | **Optional filters** → Specifications (§7) |
| 1–3 conditions, simple ordering | Long names (`findByAAndBOrCAndDNotInOrderBy…`) → `@Query` |
| Relations one level deep (`CompanyName`) | Grouping `A and (B or C)` → `@Query` / Specification (§5) |
| Reads that return entities or projections | Bulk deletes or updates → `@Modifying` / `Delete/UpdateSpecification` (§8) |
| | Aggregations (`GROUP BY`), complex joins, reporting → `@Query` / `JdbcClient` |

**Rule of thumb:** if you have to stop and think about what a method name means, it's too long. Write a
`@Query` with a readable name, e.g. `@Query("…") List<Job> findOpenRemoteJobsForCompany(...)`.

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| Passing `null` to "skip" a filter | `IS NULL` condition, wrong results (verified) |
| Expecting `A and (B or C)` | Gets `(A and B) or C` (verified) |
| Single-result method without a unique constraint | `IncorrectResultSizeDataAccessException` later (verified) |
| Derived `deleteBy…` on many rows | 1 SELECT + N DELETEs (verified) |
| `Stream<T>` outside a transaction / not closed | Errors or leaked connections |
| `Like` with raw user input | User-controlled wildcards; prefer `Containing` (escapes them, verified) |
| `count…By > 0` for existence | Wasteful; `exists…By` uses a limit (verified) |
| Typo in a property name | App won't start (good!), with a "Did you mean …" hint (verified) |

## Interview questions

**Q: How do derived query methods work?**
At startup Spring Data parses the method name (subject, properties, operators, ordering) against the entity
model and creates a JPA query. Invalid names fail application startup.

**Q: `find…By` vs `get…By` vs `read…By`?**
Equivalent subjects. `count…By`, `exists…By` and `delete…By` change what's returned or executed.

**Q: How do `Top` / `First` work?**
They limit the result (`fetch first N rows only`), usually combined with `OrderBy`.

**Q: What happens if you pass `null` to a derived query parameter?**
It's translated to `IS NULL`. Derived queries don't skip null criteria. Use Specifications for optional filters.

**Q: What does `findByCompanyName` do if `Job` has a `company` relation?**
It traverses the relation (`company.name`) and joins the `company` table. Use `findByCompany_Name` to disambiguate.

**Q: How does a derived delete execute?**
It selects the matching entities, then removes them one by one (callbacks and versioning apply). Use a bulk
`@Modifying` query for large deletes.

**Q: When would you avoid derived queries?**
For optional or dynamic filters, complex boolean logic, aggregations, very long method names, and bulk updates or deletes.
