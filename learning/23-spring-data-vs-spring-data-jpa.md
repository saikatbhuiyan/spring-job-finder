# 23 — Spring Data vs Spring Data JPA

## Goal

"Spring Data" and "Spring Data JPA" are often used as if they meant the same thing. They don't:

- **Spring Data** is an **umbrella project**: a family of modules for different data stores, all
  built on one shared programming model (repositories).
- **Spring Data JPA** is **one module** in that family, for relational databases through
  **JPA/Hibernate**.

This note explains both and shows the difference by running the **same** repository on Spring Data
JPA and on its sibling Spring Data JDBC. Verified with Spring Boot 4.2.0, Spring Data release train
**2026.1.0**, Hibernate **7.4.15.Final** and H2. The code is a scratch demo; Job Finder adds
persistence in a later step.

Status: 🧪 learned and verified; not yet in the project.

---

## 1. Spring Data: the umbrella

Spring Data is released as a **release train**: one version number that pins compatible versions
of every module. Boot 4.2 manages train **2026.1.0** (read from Boot's dependency BOM):

| Module | Version | Data store |
|--------|---------|------------|
| **spring-data-commons** | 4.2.0 | *Shared foundation:* `Repository`, `CrudRepository`, paging, sorting, query derivation |
| **spring-data-jpa** | 4.2.0 | Relational DBs via **JPA (Hibernate)** |
| spring-data-jdbc / spring-data-relational | 4.2.0 | Relational DBs via plain JDBC (no ORM) |
| spring-data-r2dbc | 4.2.0 | Relational DBs, reactive |
| spring-data-mongodb | 5.2.0 | MongoDB (documents) |
| spring-data-redis | 4.2.0 | Redis (key-value) |
| spring-data-elasticsearch | 6.2.0 | Elasticsearch (search) |
| spring-data-cassandra | 5.2.0 | Cassandra (wide-column) |
| spring-data-neo4j | 8.2.0 | Neo4j (graph) |
| spring-data-couchbase, -ldap, -keyvalue, -envers | … | … |
| spring-data-rest | 5.2.0 | Exposes repositories as REST endpoints |

Each module has a Boot starter: `spring-boot-starter-data-jpa`, `-data-jdbc`, `-data-mongodb`,
`-data-redis`, `-data-elasticsearch`, …

### What all modules share (Spring Data Commons)

```text
Repository<T, ID>                      marker interface
 └── CrudRepository<T, ID>             save, findById, findAll, count, delete, …
      └── ListCrudRepository<T, ID>    same, returning List instead of Iterable
PagingAndSortingRepository<T, ID>      findAll(Pageable), findAll(Sort)
```

Plus:
- **Derived queries:** the query is generated from the **method name**
  (`findByCompanyOrderBySalaryDesc`).
- **Paging and sorting:** `Pageable`, `Page<T>`, `Sort`.
- **No implementation to write:** Spring generates it at startup (a proxy).

So the **way** you write data access is the same whether the data lives in PostgreSQL, MongoDB or Redis.
Only the module and the store-specific details change.

---

## 2. Spring Data JPA: one module

```text
Your JobRepository (interface)
        │  Spring Data JPA generates the implementation
        ▼
JPA API (jakarta.persistence: EntityManager, @Entity, JPQL)
        │  specification
        ▼
Hibernate 7.4 (the JPA implementation, the "ORM")
        │  generates SQL
        ▼
JDBC + HikariCP (note 22) ──▶ database
```

- **JPA** (Jakarta Persistence) is a **specification**: the standard API and annotations.
- **Hibernate** is the **implementation** that maps objects to tables and writes the SQL (an ORM:
  Object-Relational Mapper).
- **Spring Data JPA** adds the **repository layer** on top, so you don't call `EntityManager` by hand.

---

## 3. The same repository on two modules (verified)

The **same** interface declaration on Spring Data JPA and on Spring Data JDBC:

```java
public interface JobRepository extends ListCrudRepository<Job, Long> {   // (+ PagingAndSortingRepository for JDBC)
    List<Job> findByCompanyOrderBySalaryDesc(String company);           // derived query
    long countByCompany(String company);
    Page<Job> findBySalaryGreaterThan(int salary, Pageable pageable);   // paging
    @Query("...") List<Job> search(String kw);                          // custom query
}
```

The entity differs:

```java
// Spring Data JPA (jakarta.persistence)              // Spring Data JDBC (org.springframework.data)
@Entity                                               public class Job {
public class Job {                                        @Id private Long id;
    @Id @GeneratedValue(strategy = IDENTITY)              ...
    private Long id;                                  }
    ...
    protected Job() {}   // JPA requires a no-arg constructor
}
```

### Results

| Call | JPA | JDBC |
|------|-----|------|
| Repository class | `$Proxy99 implements [JobRepository, Repository]` | `$Proxy60 implements [JobRepository, Repository]` |
| `findByCompanyOrderBySalaryDesc("Acme")` | `[Senior Java Engineer@Acme(120000), Java Developer@Acme(90000)]` | same |
| `countByCompany("Acme")` | `2` | `2` |
| `findBySalaryGreaterThan(80000, page 0, size 2, salary desc)` | 2 items, `totalElements=3 totalPages=2` | same |

**We wrote no implementation in either case.** Spring Data parsed the method names and generated the
queries. That's Spring Data Commons at work.

### The generated SQL (verified, from the logs)

```sql
-- JPA (Hibernate), derived query
select j1_0.id,j1_0.company,j1_0.salary,j1_0.title from job j1_0 where j1_0.company=? order by j1_0.salary desc
-- JDBC, same method
SELECT "JOB"."ID" AS "ID", ... FROM "JOB" WHERE "JOB"."COMPANY" = ? ORDER BY "JOB"."SALARY" DESC

-- Paging = a page query + a COUNT query (both modules)
... where j1_0.salary>? order by j1_0.salary desc fetch first ? rows only
select count(j1_0.id) from job j1_0 where j1_0.salary>?
```

Both use `?` parameters, so both are safe from SQL injection (note 22 §3).

`@Query` differs: JPA uses **JPQL**, which refers to **entity** names (`select j from Job j …`). JDBC uses
**plain SQL** (`select * from job …`).

---

## 4. The real difference: change tracking (verified)

Same code in both apps: load a job inside a transaction, change its salary, and **don't call `save()`**:

```java
tx.executeWithoutResult(s -> repo.findById(id).orElseThrow().setSalary(99000));
```

**Spring Data JPA:**

```sql
select ... from job j1_0 where j1_0.id=?
update job set company=?,salary=?,title=? where id=?      ← nobody called save()!
```

```text
salary in DB afterwards: 99000
```

**Spring Data JDBC:**

```sql
SELECT ... FROM "JOB" WHERE "JOB"."ID" = ?
                                                           ← no UPDATE
```

```text
salary in DB afterwards: 85000
```

**Why:** JPA has a **persistence context**, a per-transaction cache that keeps track of every entity it
loaded. At commit, Hibernate compares each entity with its original state (**dirty checking**) and
writes the changes. Spring Data JDBC has no persistence context: an object is just an object until you
call `save()`.

| | Spring Data JPA | Spring Data JDBC |
|-|-----------------|------------------|
| Model | ORM: managed entities with state | Simple: load = SELECT, save = INSERT/UPDATE |
| Change tracking | ✅ Automatic (dirty checking) | ❌ Call `save()` explicitly |
| Lazy loading of relationships | ✅ (and its traps: N+1 queries, `LazyInitializationException`) | ❌ None; aggregates load fully |
| Caching | First-level (per transaction), optional second-level | None |
| Schema | Can generate DDL (**dev only**) | You provide the schema |
| Query language | JPQL / Criteria API + native SQL | SQL |
| Entity requirements | `@Entity`, no-arg constructor, usually mutable | Plain classes or records |
| Mental model | Powerful but more "magic" | Predictable, explicit |
| Best for | Rich domain models with relationships | Simple aggregates, CQRS-style reads |

---

## 5. Production notes the demo surfaced

1. **Schema generation is for demos only.** With an embedded H2, Boot let Hibernate **create the table
   automatically** (`create table job (...)`) and **drop it on shutdown** (`drop table if exists job
   cascade`). In production the schema must be **versioned and reviewed**: **Flyway** migrations, with
   `spring.jpa.hibernate.ddl-auto=validate` so Hibernate only *checks* that the entities match.
2. **Generated column types are sloppy.** Hibernate created `company varchar(255)` and `title varchar(255)`,
   **nullable**, while the hand-written JDBC schema had `NOT NULL` and real lengths. Another reason to own
   the schema in migrations.
3. **`spring.jpa.open-in-view=false`.** Boot enables "Open Session in View" by default, which keeps a
   database connection for the whole HTTP request so lazy loading works in controllers. It hides N+1
   query problems and holds connections longer than needed. Production apps disable it (as in the demo)
   and load what they need in the service layer.
4. **Dirty checking is powerful but implicit.** Any entity changed inside a transaction gets written,
   even by accident. Keep entity changes inside **domain methods** (`job.close()`, note 19 §6) and
   transactions in the **application** layer.

---

## 6. Which one for Job Finder? (🔜 decided when persistence is added)

Job Finder's domain has real relationships (company → jobs → applications → users) and business rules
on entities (`job.close()`). **Spring Data JPA** is the likely fit, together with:

- **Flyway** for schema migrations (`ddl-auto=validate`);
- `open-in-view=false`;
- `JdbcClient` (note 22) for read-heavy search queries where hand-written SQL is clearer;
- **Testcontainers** PostgreSQL in tests.

It will be recorded as an ADR with the alternatives (Spring Data JDBC, jOOQ) when implemented.

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Saying "Spring Data" when you mean "Spring Data JPA" | Confusion: Spring Data also covers MongoDB, Redis, … |
| `ddl-auto=update`/`create` in production | Uncontrolled schema changes, possible data loss |
| Not knowing about dirty checking | Unexpected UPDATEs; or calling `save()` everywhere "just in case" |
| Leaving `open-in-view=true` | Hidden N+1 queries, connections held for the whole request |
| Expecting JDBC-style behavior from JPA (or the reverse) | Changes saved unexpectedly / not saved |
| Very long derived method names | Unreadable; use `@Query` instead |

## Interview questions

**Q: What is Spring Data?**
An umbrella project that provides a consistent repository-based programming model across many data
stores (JPA, JDBC, MongoDB, Redis, Elasticsearch, Cassandra, Neo4j…), built on Spring Data Commons.

**Q: What is Spring Data JPA?**
The Spring Data module for relational databases through JPA. It generates repository implementations on
top of a JPA provider (Hibernate by default).

**Q: JPA vs Hibernate vs Spring Data JPA?**
JPA is the specification. Hibernate is an implementation (ORM). Spring Data JPA is a repository
abstraction on top of JPA that removes boilerplate.

**Q: What is a derived query?**
A query Spring Data generates from the repository method name, e.g. `findByCompanyOrderBySalaryDesc`.

**Q: Spring Data JPA vs Spring Data JDBC?**
JPA is a full ORM with a persistence context (dirty checking, lazy loading, caching). JDBC is simpler:
no persistence context, explicit `save()`, aggregates loaded fully, SQL only.

**Q: What is dirty checking?**
JPA tracks managed entities within a transaction and automatically writes changes at commit, without an
explicit `save()`.

**Q: Why disable open-in-view?**
It keeps a connection open for the whole web request so lazy loading works in the view or controller,
which hides N+1 query problems and reduces scalability.
