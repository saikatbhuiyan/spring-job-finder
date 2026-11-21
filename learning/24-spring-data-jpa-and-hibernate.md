# 24 — How Spring Data JPA and Hibernate Are Related

## Goal

Note 23 showed the stack as a diagram. This note **traces it inside a running application**: which
class does what when you call `jobRepository.save(job)`, where Spring stops and Hibernate starts, and
why that matters for debugging. Verified with Spring Boot 4.2.0, Spring Data JPA 4.2.0 and Hibernate
7.4.15 (a scratch demo, not yet in the project).

Status: 🧪 learned and verified.

---

## 1. One sentence

**Spring Data JPA is a repository layer *on top of* the JPA API; Hibernate is the JPA *implementation*
underneath that does the actual work: mapping objects to tables, generating SQL, tracking changes.**

Spring Data JPA never talks to the database itself. It translates your repository calls into JPA calls
(`EntityManager.persist`, `merge`, JPQL queries), and Hibernate executes them.

```text
 You write                    JobRepository (interface only)
 ─────────────────────────────────────────────────────────────────────────────
 Spring Data JPA 4.2          SimpleJpaRepository   ← generated implementation of save/findById/…
                              + query derivation    ← findByCompany… → JPA query
 ─────────────────────────── JPA API (jakarta.persistence) ──────────────────
 Spring ORM (glue)            shared EntityManager proxy, JpaTransactionManager
 ─────────────────────────────────────────────────────────────────────────────
 Hibernate 7.4                SessionFactoryImpl / SessionImpl
                              ORM mapping, persistence context, dirty checking, SQL generation
 ─────────────────────────────────────────────────────────────────────────────
 JDBC + HikariCP              HikariDataSource → JDBC driver → database   (note 22)
```

---

## 2. Who is who at runtime (verified)

Printed from the running scratch app:

```text
JobRepository bean            -> $Proxy97 (proxy), target = org.springframework.data.jpa.repository.support.SimpleJpaRepository
EntityManagerFactory (bean)   -> $Proxy91 (Spring proxy), native = org.hibernate.internal.SessionFactoryImpl
JPA persistence provider      -> org.springframework.orm.jpa.vendor.SpringHibernateJpaPersistenceProvider
JPA vendor adapter            -> org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
injected EntityManager        -> $Proxy92 (Spring's shared, transaction-bound proxy)
...inside a transaction       -> delegate = org.hibernate.internal.SessionImpl
Transaction manager           -> org.springframework.orm.jpa.JpaTransactionManager
DataSource (connection pool)  -> com.zaxxer.hikari.HikariDataSource
```

| Object | Belongs to | Role |
|--------|------------|------|
| `$Proxy97` → **`SimpleJpaRepository`** | Spring Data JPA | Implements `save`, `findById`, `findAll`, `delete`… for **every** repository |
| `EntityManagerFactory` → **`SessionFactoryImpl`** | Hibernate | Created once at startup: holds all entity mappings and SQL metadata (expensive, thread-safe) |
| `EntityManager` → **`SessionImpl`** | Hibernate | Per transaction: the **persistence context** that tracks loaded entities (cheap, not thread-safe) |
| `SpringHibernateJpaPersistenceProvider`, `HibernateJpaVendorAdapter` | Spring ORM | Spring Boot's glue that boots Hibernate as the JPA provider |
| `$Proxy92` shared `EntityManager` | Spring ORM | One injectable, thread-safe proxy that routes to the **current transaction's** `SessionImpl` |
| `JpaTransactionManager` | Spring ORM | Starts and commits JPA transactions for `@Transactional` |
| `HikariDataSource` | HikariCP | Connection pool (note 22 §6) |

**JPA ↔ Hibernate naming:**

| JPA (standard interface) | Hibernate (implementation) |
|--------------------------|----------------------------|
| `EntityManagerFactory` | `SessionFactory` (`SessionFactoryImpl`) |
| `EntityManager` | `Session` (`SessionImpl`) |
| JPQL | HQL (a superset of JPQL) |
| `@Entity`, `@Id`, … | Same annotations, plus Hibernate-specific extras |

> **Debugging trap (hit while writing this note):** `em.unwrap(Session.class)` and
> `emf.unwrap(SessionFactory.class)` printed `jdk.proxy2.$Proxy92` / `$Proxy91`, *not* Hibernate
> classes. Spring's proxies implement those Hibernate interfaces themselves, so `unwrap` returned the
> proxy. `emfBean.getNativeEntityManagerFactory()` and `em.getDelegate()` (inside a transaction)
> reveal the real `SessionFactoryImpl` / `SessionImpl`.

---

## 3. Following `save()` through the layers (verified)

`SimpleJpaRepository.save(entity)` asks **"is this entity new?"** (by default: is its `@Id` `null`?):

```text
isNew  → entityManager.persist(entity)   (Hibernate schedules an INSERT)
!isNew → entityManager.merge(entity)     (Hibernate loads the current row, copies changes, UPDATE)
```

**New entity (`id == null`):**

```sql
insert into job (company,salary,title,id) values (?,?,?,default)
-- saved id=1
```

**Detached object with an existing id** (e.g. built from a request):

```sql
select j1_0.id,j1_0.company,j1_0.salary,j1_0.title from job j1_0 where j1_0.id=?   ← merge loads it first
update job set company=?,salary=?,title=? where id=?
```

So `save()` on an existing entity costs an **extra SELECT**. For a **managed** entity (loaded in the same
transaction) you don't need `save()` at all: dirty checking writes the change (note 23 §4).

---

## 4. Same work without Spring Data (verified)

Spring Data JPA is a convenience, not a requirement. The same operations with the JPA `EntityManager`
directly, which is what `SimpleJpaRepository` does internally:

```java
tx.executeWithoutResult(s -> {
    em.persist(new Job("Go Developer", "Initech", 95000));
    List<Job> jobs = em.createQuery("select j from Job j where j.company = :c", Job.class)
                       .setParameter("c", "Initech").getResultList();
});
```

```sql
insert into job (company,salary,title,id) values (?,?,?,default)
select j1_0.id,j1_0.company,j1_0.salary,j1_0.title from job j1_0 where j1_0.company=?
-- JPQL via EntityManager -> [Go Developer@Initech(95000)]
```

Identical SQL, because **Hibernate** generates it either way. And when JPA isn't enough, you can drop to
**Hibernate's own API**:

```java
em.unwrap(Session.class).createSelectionQuery("select count(*) from Job", Long.class).getSingleResult();
// select count(*) from job j1_0   → 2 jobs
```

---

## 5. Who validates your queries? (verified)

A typo in a `@Query`:

```java
@Query("select j from Job j where j.compnay = :c")   // compnay
List<Job> byCompany(String c);
```

The **application fails at startup**, and the exception chain shows the division of labor:

```text
BeanCreationException: Error creating bean with name 'jobRepository' ...
QueryCreationException: Cannot create query for method ...                      ← Spring Data JPA
IllegalArgumentException: org.hibernate.query.sqm.UnknownPathException: ...
UnknownPathException: Could not resolve attribute 'compnay' of 'demo.Job'       ← Hibernate's parser (SQM)
```

Spring Data JPA **creates** the query when the repository is built. Hibernate **parses and checks** it
against the entity model. That's another reason for fail-fast eager startup (note 14 §6): a typo is
caught when the app starts, not on a user's request.

---

## 6. Who does what

| Concern | Spring Data JPA | Hibernate |
|---------|-----------------|-----------|
| Repository interfaces, generated implementations | ✅ | |
| Derived queries from method names | ✅ (builds the query) | executes it |
| Paging (`Pageable`) → page query + count query | ✅ | executes them |
| Object ↔ table mapping (`@Entity`, columns, relations) | | ✅ |
| Persistence context, dirty checking, flush | | ✅ |
| Lazy loading, proxies for relations | | ✅ |
| SQL generation, SQL dialects (PostgreSQL, H2…) | | ✅ |
| Parsing and validating JPQL/HQL | | ✅ |
| First-level / second-level cache | | ✅ |
| Schema generation (`ddl-auto`) | | ✅ |
| Transactions (`@Transactional`) | (Spring Framework's `JpaTransactionManager`) | the underlying JDBC transaction |

**Consequence for debugging:**
- A *"method name can't be parsed"* or *"No property X found for type Job"* error → **Spring Data**
  (check the method name).
- Wrong SQL, N+1 queries, `LazyInitializationException`, unexpected UPDATEs → **Hibernate**. Turn on
  `logging.level.org.hibernate.SQL=DEBUG` (as in these demos) and read the generated SQL.

---

## 7. Could you replace Hibernate?

In principle, yes. JPA is a standard, and another provider (e.g. EclipseLink) could sit under Spring Data
JPA. In practice, Spring Boot's `spring-boot-starter-data-jpa` brings **Hibernate**, and almost every
Spring project uses it. Code that sticks to JPA APIs stays portable. Code that uses `Session` or
Hibernate-specific annotations doesn't.

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Thinking Spring Data JPA "is" the ORM | Debugging in the wrong place; mapping and SQL are Hibernate's job |
| Calling `save()` on already-managed entities "to be safe" | Usually harmless but noisy; on detached objects it costs an extra SELECT (merge) |
| Building entities from request data and calling `save()` | `merge` copies **every** field, which can overwrite columns the client never sent. Load the entity and change it through domain methods instead |
| Injecting `EntityManagerFactory` and creating `EntityManager`s by hand | They aren't bound to Spring transactions; inject `EntityManager` (Spring's shared proxy) |
| Trusting `unwrap()` on Spring proxies when debugging | You may get the proxy, not the Hibernate object (§2) |

## Interview questions

**Q: How are Spring Data JPA and Hibernate related?**
Spring Data JPA is an abstraction over the JPA API that generates repository implementations. Hibernate is the
JPA provider that actually performs the ORM work and generates SQL. Spring Data JPA calls JPA, and Hibernate
implements JPA.

**Q: What does `SimpleJpaRepository.save()` do?**
If the entity is new (id `null` by default) it calls `EntityManager.persist` (INSERT); otherwise
`EntityManager.merge` (SELECT + UPDATE).

**Q: `EntityManagerFactory` vs `EntityManager`?**
The factory (Hibernate `SessionFactory`) is created once, is thread-safe and holds the mapping metadata.
An `EntityManager` (Hibernate `Session`) is a short-lived, non-thread-safe unit of work with its own
persistence context, usually one per transaction.

**Q: How can one injected `EntityManager` be used safely by many threads?**
Spring injects a shared proxy that delegates to the `EntityManager` bound to the current thread's transaction.

**Q: JPQL vs HQL?**
JPQL is the JPA standard query language. HQL is Hibernate's, a superset of JPQL. Both query entities, not tables.

**Q: Can Spring Data JPA work without Hibernate?**
With another JPA provider (e.g. EclipseLink), yes. JPA is a specification. Spring Boot defaults to Hibernate.
