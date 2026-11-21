# 25 — Important Components of Spring Data JPA

## Goal

A tour of the Spring Data JPA building blocks you'll use in a real application, each **verified with
the SQL Hibernate actually ran**. The demo is a mini Job Finder model (`Company` 1 → * `Job`) on Spring
Boot 4.2.0 / Spring Data JPA 4.2.0 / Hibernate 7.4.15 / H2, in a scratch app.

Builds on notes 22–24. Status: 🧪 verified in a demo; these are the components Job Finder's
persistence will use.

---

## Map of the components

| # | Component | What it gives you |
|---|-----------|-------------------|
| 1 | **Entity** (`@Entity`, `@Id`, `@Column`, relations) | The object ↔ table mapping |
| 2 | **Repository interfaces** (`JpaRepository`, `JpaSpecificationExecutor`) | CRUD and query methods, no implementation needed |
| 3 | **Query methods**: derived, `@Query` (JPQL/native), `@Modifying` | Reading and bulk-writing data |
| 4 | **Paging and sorting**: `Pageable`, `Page`, `Slice`, `Sort` | Large result sets |
| 5 | **Projections**: interface, DTO/record | Fetching only what you need |
| 6 | **Fetching**: `LAZY`, `@EntityGraph` | Avoiding N+1 queries |
| 7 | **Specifications** | Dynamic, composable search criteria |
| 8 | **Auditing**: `@CreatedDate`, `@LastModifiedDate` | Automatic timestamps (and users) |
| 9 | **Optimistic locking**: `@Version` | Safe concurrent updates |
| 10 | **Transactions** | Every repository method runs in one |
| 11 | **Configuration**: Boot auto-config, `spring.jpa.*` | Wiring and behavior |

---

## 1. Entity

```java
@Entity
@Table(name = "job")
@EntityListeners(AuditingEntityListener.class)          // for auditing (§8)
public class Job {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 200)               private String title;
    @Column(nullable = false)                             private int salary;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.OPEN;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)  private Company company;   // relation (§6)
    @Version                                              private long version;      // locking (§9)
    @CreatedDate private Instant createdAt;  @LastModifiedDate private Instant updatedAt;
    protected Job() {}                                     // required by JPA
    ...
}
```

Key points:
- `@Enumerated(EnumType.STRING)` stores `OPEN`/`CLOSED`. **Never use the default `ORDINAL`** (0, 1): adding
  an enum constant in the middle silently corrupts existing data.
- `@Column(nullable = false, length = …)` documents the constraints (the real schema will come from Flyway).
- `@ManyToOne(fetch = LAZY)`: the default for `@ManyToOne` is **EAGER**. Make it `LAZY` explicitly (§6).

⚠️ **Trap hit in the demo:** wildcard imports of `jakarta.persistence.*` **and**
`org.springframework.data.annotation.*` failed to compile with **`reference to Id is ambiguous`**. Both
packages have an `@Id`. A JPA entity must use **`jakarta.persistence.Id`**; Spring Data's `@Id` is for
non-JPA modules (JDBC, MongoDB). Use explicit imports.

---

## 2. Repository interfaces

```text
Repository                               (Spring Data Commons, marker)
 └ CrudRepository → ListCrudRepository   save, findById, findAll, count, delete…
PagingAndSortingRepository → ListPagingAndSortingRepository
JpaRepository<T, ID>                     (Spring Data JPA) all of the above, plus
                                         flush, saveAndFlush, deleteAllInBatch, getReferenceById…
JpaSpecificationExecutor<T>              findAll(Specification), count(Specification)… (§7)
QueryByExampleExecutor<T>                findAll(Example) (query by example, not used here)
```

```java
public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> { ... }
```

Choose the **smallest** interface that fits. `ListCrudRepository` is enough when you don't need
JPA-specific methods. Expose fewer methods and there's less to misuse.

---

## 3. Query methods

**Derived queries:** the method name is the query (notes 23–24).

**`@Query` with JPQL**, for anything a name can't express nicely:

```java
@Query("select new demo.JobSummary(j.title, j.company.name) from Job j order by j.title")
List<JobSummary> summaries();
```

⚠️ **Trap hit in the demo:** with `JobSummary` as a record **nested** inside `JobRepository`, startup failed:

```text
QueryCreationException: Cannot create query for method [JobRepository.summaries()];
SemanticException: Could not resolve class 'demo.JobRepository.JobSummary' named for instantiation
```

JPQL's `select new` needs the class's **binary** name (`demo.JobRepository$JobSummary`) for nested classes.
Making the DTO a **top-level** record fixed it, and it's cleaner anyway. The good news: the error came **at
startup**, because Spring Data validates `@Query` strings eagerly (note 24 §5).

**`@Modifying` bulk updates:**

```java
@Modifying
@Query("update Job j set j.status = demo.JobStatus.CLOSED where j.salary < :min")
int closeLowPaying(int min);
```

```sql
update job j1_0 set status='CLOSED' where j1_0.salary<?      -- one statement → "closed 1 job(s)"
```

- It needs a **transaction** (call it from a `@Transactional` service method).
- ⚠️ It **bypasses the persistence context**: entities already loaded in the same transaction still show the
  old values. Use `@Modifying(clearAutomatically = true)`, or run bulk operations in their own transaction.
- It skips entity callbacks, auditing and `@Version`. Fine for bulk jobs, but not a replacement for
  domain methods.

---

## 4. Paging and sorting: `Page` vs `Slice`

```java
Page<Job>  findPageByStatus(JobStatus status, Pageable p);
Slice<Job> findSliceByStatus(JobStatus status, Pageable p);

PageRequest.of(0, 2, Sort.by("salary").descending())
```

Verified:

```sql
-- Slice: ONE query (fetches size+1 rows to know if there's a next page)
select ... from job j1_0 where j1_0.status=? order by j1_0.salary desc fetch first ? rows only
--   → slice: 2 items, hasNext=true

-- Page: the same query PLUS a count query
select ... from job j1_0 where j1_0.status=? order by j1_0.salary desc fetch first ? rows only
select count(j1_0.id) from job j1_0 where j1_0.status=?
--   → page: 2 items, total=4
```

| | `Page` | `Slice` |
|-|--------|---------|
| Knows the total number of results / pages | ✅ | ❌ |
| Extra `COUNT` query | ✅ (can be expensive on big tables) | ❌ |
| UI fit | "Page 3 of 12" | Infinite scroll / "Load more" |

**Production:** always **limit page size** (`size=100000` must not be allowed), and always **sort** (otherwise
row order isn't guaranteed and pages can overlap).

---

## 5. Projections: fetch only what you need

**Interface projection:**

```java
interface JobTitleView { String getTitle(); int getSalary(); }
List<JobTitleView> findByStatus(JobStatus status);
```

```sql
select j1_0.title,j1_0.salary from job j1_0 where j1_0.status=?      -- only 2 columns!
```

**DTO / record projection** (constructor expression):

```sql
select j1_0.title,c1_0.name from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id order by j1_0.title
-- [JobSummary[title=Go Developer, companyName=Initech], …]
```

Projections are **read-only** (not tracked by the persistence context, so no dirty checking), and they fetch
only the selected columns. Ideal for **lists and search results**, which is most of Job Finder's reads. Load
full entities only when you're going to **change** them.

---

## 6. Fetching: the N+1 problem and `@EntityGraph`

Load 4 jobs, then show each job's company name (`job.getCompany().getName()`):

```sql
select ... from job j1_0 order by j1_0.title                       -- 1 query for the jobs
select c1_0.id,c1_0.name from company c1_0 where c1_0.id=?         -- + 1 per company
select c1_0.id,c1_0.name from company c1_0 where c1_0.id=?
select c1_0.id,c1_0.name from company c1_0 where c1_0.id=?
```

That's **N+1**: 1 query for the list, plus one more per related entity. Here it's 1 + 3, not 1 + 4, because
Acme was already in the persistence context after its first load. With 50 jobs per page, a page could run 51
queries. It's the most common JPA performance problem.

**Fix:** tell JPA to fetch the relation in the same query:

```java
@EntityGraph(attributePaths = "company")
List<Job> findWithCompanyByOrderByTitle();
```

```sql
select j1_0.id,...,c1_0.id,c1_0.name,... from job j1_0 join company c1_0 on c1_0.id=j1_0.company_id order by j1_0.title
-- ONE query, same output
```

Other fixes: `join fetch` in a `@Query`, or a projection that selects the company name (§5). Keep relations
`LAZY` by default and fetch **explicitly per use case**. `EAGER` everywhere causes over-fetching and hidden joins.

---

## 7. Specifications: dynamic search

Job search has optional filters (keyword? minimum salary? company?). Writing a method for every combination is
impossible. **Specifications** are small, composable conditions:

```java
static Specification<Job> titleContains(String kw) {
    return (root, query, cb) -> kw == null ? null : cb.like(cb.lower(root.get("title")), "%" + kw.toLowerCase() + "%");
}
static Specification<Job> minSalary(Integer min) {
    return (root, query, cb) -> min == null ? null : cb.ge(root.get("salary"), min);
}
static Specification<Job> companyName(String name) {
    return (root, query, cb) -> name == null ? null : cb.equal(root.get("company").get("name"), name);
}

jobs.findAll(Specification.allOf(titleContains("java"), minSalary(100000), companyName(null)));
```

```sql
select ... from job j1_0 where lower(j1_0.title) like ? escape '' and j1_0.salary>=?
-- → [Senior Java Engineer]       (the null company filter was simply left out)
```

Returning `null` means "no condition", so each filter is optional. Values are bound as parameters, so this is
safe from SQL injection. **This is how Job Finder's `GET /api/v1/jobs?keyword=&minSalary=&company=` will work.**

---

## 8. Auditing: automatic timestamps

```java
@SpringBootApplication
@EnableJpaAuditing                               // switch auditing on
public class App { ... }

@Entity @EntityListeners(AuditingEntityListener.class)
public class Job {
    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;
}
```

```text
createdAt=2026-11-20T17:42:59.468882Z updatedAt=2026-11-20T17:42:59.468882Z version=0
after update: updatedAt changed=true version=1
```

`@CreatedBy` / `@LastModifiedBy` also record **who** made the change, once Spring Security supplies the
current user through an `AuditorAware` bean (🔜). Use `Instant` (UTC) for timestamps, not `LocalDateTime`
(which has no time zone).

---

## 9. Optimistic locking: `@Version`

Two recruiters edit the same job at the same time. Without protection, the **last save silently overwrites**
the other one ("lost update").

```java
@Version private long version;
```

Every update checks and increments the version:

```sql
update job set ..., version=? where id=? and version=?
```

Verified: after one update (version 0 → 1), saving the **stale copy** loaded earlier (still version 0):

```text
saving a STALE copy -> ObjectOptimisticLockingFailureException
```

The update matched 0 rows (the version had changed), so Spring threw instead of overwriting. The API will map
this to **`409 Conflict`** (Problem Details): "this job was changed by someone else, reload and try again."

**Optimistic** = no database locks held; detect conflicts at write time. Ideal for web apps where conflicts are
rare. (`@Lock(PESSIMISTIC_WRITE)` locks rows while a transaction is running; use it only when conflicts are
frequent and retries are expensive.)

---

## 10. Transactions

Verified by reflection: **`SimpleJpaRepository` is annotated `@Transactional(readOnly = true)`**, and its write
methods (`save`, `delete`…) override that with `@Transactional`. So every repository call runs in a
transaction even if you don't declare one.

But a **use case** usually spans several repository calls, so put `@Transactional` on the **application service**
method (note 19: the application layer owns transactions). Mark read use cases `@Transactional(readOnly = true)`,
which lets Hibernate skip dirty checking.

---

## 11. Configuration (Spring Boot)

With `spring-boot-starter-data-jpa`, Boot auto-configures the `DataSource` (HikariCP), the
`EntityManagerFactory` (Hibernate), the `JpaTransactionManager` and **repository scanning**
(`@EnableJpaRepositories` is implied). Production-relevant properties:

```properties
spring.jpa.open-in-view=false                 # note 23 §5
spring.jpa.hibernate.ddl-auto=validate        # schema comes from Flyway migrations, Hibernate only validates
logging.level.org.hibernate.SQL=DEBUG         # dev only: see generated SQL (as in these demos)
```

---

## Production checklist for Job Finder's persistence (🔜)

1. Entities in `domain`, repository interfaces in `domain` (note 19, ADR 0001).
2. `@ManyToOne(fetch = LAZY)`, and fetch relations explicitly per use case (`@EntityGraph` / projections).
3. **Projections for read endpoints**, entities only for writes.
4. `Specification`s for search; **paging with a max size** and a stable sort.
5. `@Version` on every entity users edit → `409 Conflict` on stale updates.
6. Auditing (`createdAt`, `updatedAt`, later `createdBy`).
7. `@Enumerated(STRING)`, `Instant` timestamps, explicit column constraints.
8. `@Transactional` on application services, `readOnly` for queries.
9. Flyway owns the schema (`ddl-auto=validate`), `open-in-view=false`.
10. Tests against real PostgreSQL (Testcontainers), including a **query-count** check for N+1 on list endpoints.

---

## Common mistakes

| Mistake | Consequence (verified where marked) |
|---------|-------------------------------------|
| Mixing up `jakarta.persistence.Id` and Spring Data's `@Id` | Compile error, or a broken mapping (verified) |
| Nested DTO class in a `select new` JPQL | Startup failure (verified) |
| `EnumType.ORDINAL` | Data corruption when enum order changes |
| Default EAGER `@ManyToOne` / lazy relations accessed in loops | N+1 queries (verified: 1 + 3) |
| `Page` for infinite scroll on huge tables | Expensive `COUNT` on every request (verified: extra query) |
| No `@Version` on editable entities | Silent lost updates |
| Expecting `@Modifying` updates to refresh loaded entities | Stale in-memory state |
| Returning entities from controllers | Lazy-loading errors, leaked fields; use DTOs (note 16) |

## Interview questions

**Q: `CrudRepository` vs `JpaRepository`?**
`CrudRepository` (Spring Data Commons) has the basic CRUD methods. `JpaRepository` adds JPA-specific operations
(flush, batch deletes, `getReferenceById`) plus paging and sorting.

**Q: What is the N+1 problem and how do you fix it?**
One query loads N parent rows, then N extra queries load a lazy relation. Fix it with `@EntityGraph`, `join fetch`,
or projections that select the needed columns.

**Q: `Page` vs `Slice`?**
`Page` runs an extra count query and knows the total. `Slice` only knows whether there's a next page. It's cheaper,
good for infinite scroll.

**Q: What are projections?**
Read-only views that select only some fields: interface projections, or DTO/record projections. They reduce data
transfer and avoid dirty checking.

**Q: What are Specifications?**
Composable, type-safe predicates (based on the JPA Criteria API) for building dynamic queries from optional filters.

**Q: How does optimistic locking work in JPA?**
A `@Version` column is checked and incremented on every update (`where id=? and version=?`). If no row matches, the
update fails with `OptimisticLockException` (Spring: `ObjectOptimisticLockingFailureException`).

**Q: How do you enable auditing?**
`@EnableJpaAuditing`, `@EntityListeners(AuditingEntityListener.class)` on the entity, and
`@CreatedDate`/`@LastModifiedDate` (and `@CreatedBy`/`@LastModifiedBy` with an `AuditorAware` bean).

**Q: Are Spring Data JPA repository methods transactional?**
Yes. `SimpleJpaRepository` is `@Transactional(readOnly = true)`, and write methods override it with
`@Transactional`. Use-case transactions belong on service methods.
