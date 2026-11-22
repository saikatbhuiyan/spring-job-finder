# 28 — Spring Data JPA `@Query` Annotation

## Goal

Derived queries (note 27) are good for short, fixed lookups. When a query needs joins, grouping, a
projection, bulk updates or a name you can actually read, you write it yourself with **`@Query`**.
This note covers JPQL and native queries, parameters, projections, paging, sorting, `LIKE`, bulk
`@Modifying` updates and deletes, and the traps. Each one shows **the SQL it actually produced**.

Verified in a scratch app (Boot 4.2.0, Spring Data JPA 4.2.0, Hibernate 7.4.15, H2) with SQL logging.
Status: 🧪 verified.

---

## 1. The basics

```java
public interface JobRepository extends JpaRepository<Job, Long> {

    @Query("select j from Job j where j.company.name = :company and j.salary >= :minSalary order by j.salary desc")
    List<Job> findWellPaid(@Param("company") String company, @Param("minSalary") int minSalary);
}
```

```sql
select j1_0.id, … from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id
where c1_0.name=? and j1_0.salary>=? order by j1_0.salary desc
-- findWellPaid("Acme", 100000) → [Senior Java Engineer]
```

- The string is **JPQL** (Hibernate accepts **HQL**, a superset; see note 24). It queries **entities and
  fields** (`Job`, `j.company.name`), **not tables and columns**.
- `j.company.name` walks the relation, and Hibernate generates the `join`.
- **`@Query` wins over the method name.** You can call the method `findWellPaid` instead of
  `findByCompanyNameAndSalaryGreaterThanEqualOrderBySalaryDesc`.
- JPQL is **validated at startup** by Hibernate's parser. A typo like `j.compnay` stops the app
  (note 24 §5).

---

## 2. Parameters (verified)

| Style | Example | Notes |
|-------|---------|-------|
| **Named** `:name` + `@Param` | `where j.title = :title` + `@Param("title")` | ✅ Preferred: readable, order-independent |
| Named without `@Param` | `where j.title = :title` + `byTitle(String title)` | Works because **Boot's parent POM compiles with `-parameters`** (verified in `spring-boot-starter-parent-4.2.0.pom`), so Java keeps parameter names |
| Positional `?1`, `?2` | `where j.status = ?1 and j.remote = ?2` | Works, but breaks silently if you reorder parameters |

All three became **bind parameters** (`?`) in SQL. **Values are never pasted into the SQL text**, so
user input can't change the query. Verified with `searchSpringLike("' or '1'='1")` → `[]` (no rows,
no injection).

The query string in an annotation must be a **compile-time constant**, so you *can't* concatenate user
input into it. That is a safety net built into `@Query`.

---

## 3. Fetching relations: N+1 vs `join fetch` (verified)

`Job.company` is `LAZY`. Load OPEN jobs, then read each company's name:

```java
@Query("select j from Job j where j.status = :status")
List<Job> openPlain(JobStatus status);
```

```sql
select … from job j1_0 where j1_0.status=?              -- 1 query for the jobs
select c1_0.id,c1_0.name from company c1_0 where c1_0.id=?   -- + 1 per distinct company
select c1_0.id,c1_0.name from company c1_0 where c1_0.id=?
```

With `join fetch`, the companies come in the **same** query:

```java
@Query("select j from Job j join fetch j.company where j.status = :status")
List<Job> openWithCompany(JobStatus status);
```

```sql
select j1_0.id, …, c1_0.id, c1_0.name, … from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id where j1_0.status=?
-- one query, same result [Acme, Acme, Globex, Globex]
```

`join fetch` in `@Query` and `@EntityGraph` (note 25 §6) both fix N+1. Use `@EntityGraph` on simple
or derived methods, and `join fetch` when you write the JPQL anyway.

---

## 4. Projections: return only what you need (verified)

### a) Constructor expression → record (DTO)

```java
public record JobSummary(String title, String companyName) {}

@Query("select new demo.JobSummary(j.title, c.name) from Job j join j.company c where j.remote = true")
List<JobSummary> remoteSummaries();
```

```sql
select j1_0.title,c1_0.name from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id where j1_0.remote=true
-- [JobSummary[title=Senior Java Engineer, companyName=Acme], JobSummary[title=Python Developer, companyName=Globex]]
```

Only the two columns are selected, and nothing is managed by the persistence context, so it's cheap and read-only.
`new` needs the **fully qualified class name**, and the record must be a **top-level** class. (A nested
record failed to resolve in an earlier demo.)

**Without `new`** (`select j.title, c.name from …` with return type `List<JobSummary>`), Spring Data
JPA 4 / Hibernate 7 also mapped the result to the record's constructor (verified, same SQL). The explicit
`new` form is clearer and works on any version, so prefer it.

### b) Interface projection with aliases

```java
public interface JobView { String getTitle(); int getSalary(); String getCompanyName(); }

@Query("select j.title as title, j.salary as salary, c.name as companyName from Job j join j.company c order by j.salary desc")
List<JobView> views();
```

```sql
select j1_0.title,j1_0.salary,c1_0.name from job j1_0 join company c1_0 … order by j1_0.salary desc
-- [Senior Java Engineer/Acme/120000, Java Developer/Acme/90000, …]
```

The **aliases must match the getter names** (`as companyName` → `getCompanyName()`).

### c) Aggregations (`GROUP BY`): something derived queries can't do

```java
public interface CompanyStats { String getCompany(); long getJobs(); double getAvgSalary(); }

@Query("select c.name as company, count(j) as jobs, avg(j.salary) as avgSalary " +
       "from Job j join j.company c group by c.name order by c.name")
List<CompanyStats> stats();
```

```sql
select c1_0.name,count(j1_0.id),avg(cast(j1_0.salary as float(53))) from job j1_0 join company c1_0 … group by c1_0.name order by c1_0.name
-- [Acme: 2 jobs, avg 105000.0, Globex: 3 jobs, avg 65000.0]
```

(String concatenation of **constants** with `+` is fine: it's still a compile-time constant.)

---

## 5. Native SQL queries (verified)

```java
@Query(value = "select j.title as title, j.salary as salary, c.name as companyName " +
               "from job j join company c on c.id = j.company_id where j.salary > :min order by j.salary",
       nativeQuery = true)
List<JobView> nativeViews(int min);

@NativeQuery("select count(*) from job where status = :status")      // shorthand, Spring Data JPA 3.4+
long nativeCount(String status);
```

```sql
select j.title as title, j.salary as salary, c.name as companyName from job j join company c on c.id = j.company_id where j.salary > ? order by j.salary
-- [Python Developer/Globex/85000, Java Developer/Acme/90000, Senior Java Engineer/Acme/120000]
select count(*) from job where status = ?          -- 4
```

- A native query is **plain SQL**: **tables and columns** (`job`, `company_id`), sent to the database
  **as written**. Hibernate doesn't translate it.
- Use it for database-specific features JPQL lacks, e.g. PostgreSQL full-text search, `jsonb` operators
  or window functions.
- An enum parameter in a native query is just a value. Here we pass the **string** `"OPEN"`, because the
  column stores `EnumType.STRING`.

### ⚠️ Native SQL is *not* validated at startup (verified)

```java
@NativeQuery("select * from jobz")     // typo: table "jobz"
List<Job> brokenNative();
```

The **application started normally**. It failed only when the method was **called**:

```text
InvalidDataAccessResourceUsageException: Could not prepare statement [Table "JOBZ" not found; …]
  cause SQLGrammarException
  cause JdbcSQLSyntaxErrorException: Table "JOBZ" not found
```

The same typo in JPQL stops the app at startup (note 24 §5). **Native queries need integration tests**
against the real database (Testcontainers PostgreSQL for Job Finder). Their mistakes show up only at runtime.

---

## 6. Paging (verified)

### JPQL + `Pageable`: Spring derives the count query

```java
@Query("select j from Job j where j.salary > :min")
Page<Job> pageBySalary(int min, Pageable p);
// pageBySalary(55000, PageRequest.of(0, 2, Sort.by("salary").descending()))
```

```sql
select … from job j1_0 where j1_0.salary>? order by j1_0.salary desc fetch first ? rows only
select count(j1_0.id) from job j1_0 where j1_0.salary>?          -- generated count query
-- [Senior Java Engineer, Java Developer] total=4
```

With `join fetch` on a **to-one** relation, the generated count query correctly **drops the fetch**
(`select count(j1_0.id) from job j1_0 where j1_0.status=?`, total=4 ✅).

### ⚠️ `join fetch` on a **collection** + `Pageable`: the total is wrong (verified)

```java
@Query("select c from Company c join fetch c.jobs")
Page<Company> pageWithJobs(Pageable p);              // page 0, size 1, 2 companies in the DB
```

```sql
select … from (select c1_0.id,c1_0.name from company c1_0 where exists(select 1 from job j1_0 where …)
               order by c1_0.name offset ? rows fetch first ? rows only) c1_0(id,name)
         join job j1_0 on c1_0.id=j1_0.company_id order by c1_0.name
select count(*) from company c1_0 join job j1_0 on c1_0.id=j1_0.company_id     -- ← counts JOBS, not companies
-- [Acme jobs=2] total=5     ✗  (there are only 2 companies)
```

- **The page content is correct.** Hibernate 7 pushes the pagination into a subquery over companies (no
  in-memory paging, no warning).
- **The generated count query is wrong.** It keeps the join and counts **joined rows** (5 jobs), so
  `totalElements` and `totalPages` are wrong.

**Fix: give your own `countQuery`:**

```java
@Query(value = "select c from Company c join fetch c.jobs",
       countQuery = "select count(c) from Company c")
Page<Company> pageWithJobsCounted(Pageable p);
// select count(c1_0.id) from company c1_0   → total=2 ✅
```

(The hand-written count also includes companies *without* jobs, while the inner `join fetch` skips them.
Make both queries agree on the filter you actually mean.)

### Native + `Pageable`: always give `countQuery`

```java
@NativeQuery(value = "select * from job where salary > :min",
             countQuery = "select count(*) from job where salary > :min")
Page<Job> nativePage(int min, Pageable p);           // page 1, size 2, sort title
```

```sql
select * from job where salary > ? order by title asc offset ? rows fetch next ? rows only
select count(*) from job where salary > ?
-- [Python Developer, Senior Java Engineer] total=4
```

Spring can't safely rewrite arbitrary SQL into a count, so write it yourself.

### Limiting without paging

| Way | SQL (verified) |
|-----|----------------|
| HQL `limit` in the query: `… order by j.salary desc limit 2` | `fetch first 2 rows only` (fixed) |
| A `Limit` parameter: `topN(Limit.of(3))` | `fetch first ? rows only` (chosen by the caller) |

---

## 7. Sorting: safe by default (verified)

```java
@Query("select j from Job j where j.status = :status")
List<Job> sorted(JobStatus status, Sort sort);
```

| Call | Result |
|------|--------|
| `Sort.by("salary").descending()` | `… order by j1_0.salary desc` ✅ |
| `Sort.by("length(title)")` | ❌ `InvalidDataAccessApiUsageException: Sort expression 'length(title): ASC' must only contain property references or aliases used in the select clause; If you really want to use something other than that for sorting, please use JpaSort.unsafe(…)` |
| `Sort.by("salary; drop table job")` | ❌ the **same exception**, so the injection attempt is rejected |
| `JpaSort.unsafe("length(j.title)")` | `… order by character_length(j1_0.title)` ✅ (you opted in) |

`Sort` is **appended to the query text** (`ORDER BY` can't be a bind parameter). Spring Data therefore
**validates** each sort property, and only `JpaSort.unsafe` skips the check. **Never** pass user input to
`JpaSort.unsafe`. For API sorting, map an allow-list (`?sort=salary` → `Sort.by("salary")`) and reject
anything else.

---

## 8. `LIKE` searches: Spring's `%:kw%` does **not** escape (verified)

```java
@Query("select j from Job j where j.title like %:kw%")                         // Spring Data LIKE shortcut
List<Job> searchSpringLike(String kw);

@Query("select j from Job j where lower(j.title) like lower(concat('%', :kw, '%'))")   // plain JPQL
List<Job> searchConcat(String kw);

@Query("select j from Job j where j.title like %?#{escape([0])}% escape ?#{escapeCharacter()}")
List<Job> searchEscaped(String kw);                                            // escaped
```

| Call | SQL | Result |
|------|-----|--------|
| `searchSpringLike("Developer")` | `title like ? escape ''` | `[Java Developer, Python Developer, Go Developer]` |
| `searchConcat("java")` | `lower(title) like lower(('%'\|\|?\|\|'%')) escape ''` | `[Java Developer, Senior Java Engineer]` |
| ⚠️ `searchSpringLike("%")` | `title like ? escape ''` | **all 5 jobs**: the user's `%` was a wildcard |
| ✅ `searchEscaped("%")` | `title like ? escape ?` | `[100% Remote Tester]` |

- `%:kw%` is a Spring Data shortcut. Spring adds the `%` around the value but **doesn't escape `%`/`_`
  inside it**. The derived `Containing` keyword **does** escape (note 27 §3).
- So with `@Query` and user input, use `?#{escape([0])}` + `escape ?#{escapeCharacter()}` (the SpEL
  helpers Spring Data provides), or escape the value in your service.
- This isn't SQL injection: the value is still a bind parameter. But users can make a search match
  everything, or force an expensive scan.

---

## 9. SpEL in queries: `#{#entityName}` (verified)

```java
@Query("select count(e) from #{#entityName} e")    // → select count(j1_0.id) from job j1_0   = 5
long countAllSpel();
```

`#{#entityName}` is replaced with the repository's entity name. That's useful in a generic **base
repository** shared by several entities. In a normal repository, just write `Job`.

---

## 10. Optional filters in JPQL (verified, with caveats)

```java
@Query("select j from Job j where (:status is null or j.status = :status) " +
       "and (:minSalary is null or j.salary >= :minSalary)")
List<Job> search(JobStatus status, Integer minSalary);
```

```sql
… where (? is null or j1_0.status=?) and (? is null or j1_0.salary>=?)
-- search(null, null)    → all 5 jobs
-- search(OPEN, 86000)   → [Java Developer, Senior Java Engineer]
```

It works, and unlike a derived query `null` does mean "skip" here (compare note 27 §7). But:

- **The SQL is the same for every combination**, so the database can't plan for the filters actually
  used. With several optional filters, indexes are often not used well.
- Each new filter makes the string longer and harder to read.

For Job Finder's search with many optional filters, **Specifications** (note 25 §7) build a query
containing only the filters that were given. That's the plan.

---

## 11. `@Modifying`: bulk UPDATE and DELETE (verified)

```java
@Modifying
@Query("update Job j set j.status = demo.JobStatus.CLOSED where j.salary < :max")
int closeLowPaid(int max);                       // returns the number of rows changed

@Modifying
@Query("delete from Job j where j.status = demo.JobStatus.CLOSED")
int deleteClosed();
```

```sql
update job j1_0 set status='CLOSED' where j1_0.salary<?     -- rows 1
delete from job j1_0 where j1_0.status='CLOSED'             -- deleted 2
```

**One statement** each. Compare the derived `deleteByStatus` in note 27 §8: 1 SELECT + N DELETEs.

Rules:

| Rule | What happened without it (verified) |
|------|--------------------------------------|
| **`@Modifying` is required** on UPDATE/DELETE queries | `InvalidDataAccessApiUsageException: Query executed via 'getResultList()' or 'getSingleResult()' must be a 'select' query [update Job j set j.remote = true]` |
| It needs a **transaction** | `@Transactional` on the method, or call it from a transactional service (Job Finder's application layer) |
| Enum constants in JPQL use the **fully qualified name** | `demo.JobStatus.CLOSED` |
| HQL `update` can't use implicit joins (`j.company.name`) | Use a subquery: `where j.company.id in (select c.id from Company c where c.name = :company)` |

### ⚠️ Trap 1: bulk updates bypass the persistence context (verified)

Inside **one** transaction: load a job, run `closeLowPaid(55000)`, then read the job again:

```text
select … where j1_0.id=?                              ← job loaded (status OPEN)
update job j1_0 set status='CLOSED' where j1_0.salary<?    ← rows 1
managed entity status=OPEN  (same object? true)       ← STALE: no SELECT, served from the persistence context
```

The row in the database is `CLOSED`, but the entity in memory still says `OPEN`. The bulk update went
straight to the database, so Hibernate's first-level cache never learned about it.

**Fix: `@Modifying(clearAutomatically = true)`** clears the persistence context after the update, so the
next read goes to the database:

```text
update job j1_0 set salary=(j1_0.salary+cast(? as integer)) where j1_0.company_id in (…)   ← rows 2
select … where j1_0.id=?                              ← fresh SELECT
re-read salary=121005  (same object? false)
```

**But clearing discards unsaved changes.** Pending changes must be **flushed first**. In this setup
Hibernate's default `FlushMode.AUTO` **flushed the dirty entity before the bulk statement anyway**, for
both JPQL and native `@Modifying` queries (verified: `update job set …, salary=?, … where id=? and version=?`
ran right before the bulk update, and the `+5` change survived). `flushAutomatically = true` makes that
flush explicit and independent of the flush mode, so use both flags together:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
```

### ⚠️ Trap 2: bulk updates skip `@Version`, auditing and entity callbacks (verified)

After `closeLowPaid`, the row was `status=CLOSED` but still **`version=0`** and the **same `updatedAt`**:

```text
BEFORE: status=OPEN   version=0 updatedAt=2026-11-22T15:55:38.319514Z
AFTER:  status=CLOSED version=0 updatedAt=2026-11-22T15:55:38.319514Z
```

- **Optimistic locking doesn't notice the change.** A user editing the old version won't get a conflict.
- **`@LastModifiedDate` isn't updated.** Auditing runs only on entity saves.
- **`@PreUpdate` callbacks and Hibernate events don't run.**

HQL **`update versioned`** increments the version:

```java
@Modifying @Query("update versioned Job j set j.remote = true where j.id = :id")
// update job j1_0 set remote=true,version=(j1_0.version+1) where j1_0.id=?   → version now 1
```

Set `updatedAt` yourself in the query if you need it. Rule of thumb for Job Finder:

- **Business actions on one aggregate** (close *this* job) → load the entity, call the domain method
  (`job.close()`) and let dirty checking write it. Rules, versioning and auditing all apply.
- **Real bulk operations** (expire all jobs older than 60 days) → `@Modifying` query, with the
  trade-offs above documented in the method.

---

## 12. Named queries (for completeness)

JPA can also define a query on the entity: `@NamedQuery(name = "Job.findOpen", query = "…")`. A repository
method called `findOpen` picks it up by the `Entity.method` naming convention. It keeps queries away from
the repository and hides them from readers, so modern Spring code uses `@Query` on the method instead.

---

## 13. Which query tool when?

| Need | Tool |
|------|------|
| `findByEmail`, `existsBySlug`, `countByStatus` | Derived query (note 27) |
| Fixed query with joins, `join fetch`, readable name | **`@Query` JPQL** |
| DTO / read model, `GROUP BY` reporting | **`@Query` + constructor expression / interface projection** |
| Database-specific SQL (full-text, `jsonb`, window functions) | **`@Query(nativeQuery = true)`** / `@NativeQuery`, plus integration tests |
| Many optional filters (search screens) | Specifications (note 25 §7) |
| Bulk update or delete | **`@Modifying @Query`** (with flush/clear) or `Update/DeleteSpecification` (note 26 §7) |
| Complex read-only SQL, no entities needed | `JdbcClient` (note 22) |

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| Using table/column names in JPQL (or entity names in native SQL) | Startup failure (JPQL) / runtime failure (native) |
| Trusting native SQL because "the app started" | Errors show up only when the method is called (verified) |
| Lazy relation used after a plain `select j` | N+1 queries (verified); use `join fetch` / `@EntityGraph` |
| `join fetch` on a collection + `Page` without `countQuery` | Wrong `totalElements` (5 instead of 2, verified) |
| Native `Page` query without `countQuery` | Spring has to guess a count for arbitrary SQL; always write it |
| Passing user input to `JpaSort.unsafe` | SQL injection through `ORDER BY`; plain `Sort` rejects it (verified) |
| `like %:kw%` with raw user input | `%`/`_` act as wildcards, so a search for "%" matched all rows (verified) |
| Forgetting `@Modifying` on update/delete | `InvalidDataAccessApiUsageException` (verified) |
| Reading entities after a bulk update in the same transaction | Stale values from the persistence context (verified) |
| `clearAutomatically` without a flush | Pending entity changes could be lost; use `flushAutomatically` too |
| Bulk update on versioned/audited entities | `@Version` and `updatedAt` silently not updated (verified); `update versioned` |
| Positional `?1` parameters | Silent bugs when parameters are reordered; prefer named |

## Interview questions

**Q: When do you use `@Query` instead of a derived query?**
When the query needs joins or fetch joins, projections, aggregation, custom ordering, bulk updates, or
when the derived name would be too long to read.

**Q: JPQL vs native query?**
JPQL queries entities and fields, is portable, and is validated by Hibernate at startup. A native query is
raw SQL on tables and columns, sent as written. It's needed for database-specific features but isn't
validated at startup and isn't portable.

**Q: How do `@Query` parameters prevent SQL injection?**
Values are bound as JDBC parameters (`?`), never concatenated into the SQL. The query string itself must be a
compile-time constant. `Sort` properties are validated, and only `JpaSort.unsafe` bypasses that.

**Q: Why is `@Modifying` needed?**
It tells Spring Data to run the query with `executeUpdate()` and return the affected row count. Without it,
Spring runs the query as a select and fails.

**Q: What are `clearAutomatically` and `flushAutomatically`?**
A bulk update bypasses the persistence context. `clearAutomatically` clears it afterwards so you don't read
stale entities. `flushAutomatically` writes pending changes first so clearing doesn't lose them.

**Q: Do bulk updates increment `@Version`?**
No, and they don't trigger auditing or entity callbacks either. HQL `update versioned` increments the version.

**Q: How does paging work with `@Query`?**
For JPQL, Spring derives a count query. For native queries, or when the derived count would be wrong (fetch
joins on collections), you supply `countQuery`.

**Q: How do you fix N+1 in a custom query?**
Use `join fetch` for the needed relation (or `@EntityGraph`), or project to a DTO containing only the needed columns.
