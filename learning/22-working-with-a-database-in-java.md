# 22 — Typical Steps When Working with a Database in Java (JDBC)

## Goal

Before using Spring Data JPA, understand what happens underneath: **JDBC** (Java Database
Connectivity), the standard API every Java database library is built on. This note goes through
the typical steps, shows the production problems that come with doing them by hand (SQL injection,
leaked connections, transactions, slow connections), and then shows how Spring removes the
boilerplate.

Everything was run and verified with **H2 2.5.252** (in-memory database), **HikariCP 7.1.0**
(connection pool) and **Spring JDBC 7.1.0**, the versions Spring Boot 4.2 manages. This was a
scratch demo. Job Finder gets its real database (PostgreSQL) in a later step.

Status: 🧪 learned and verified; not yet in the project.

---

## 1. The big picture

```text
Your code ──▶ JDBC API (java.sql: Connection, PreparedStatement, ResultSet)
                 │
                 ▼
            JDBC driver (one per database: org.h2.Driver, org.postgresql.Driver, …)
                 │  network protocol
                 ▼
            Database server (PostgreSQL, MySQL, H2, …)
```

JDBC is an **interface**. Each database vendor ships a **driver** that implements it. Your code talks
only to the interface, which is why switching databases is mostly a configuration change.

---

## 2. The typical steps

| # | Step | JDBC API |
|---|------|----------|
| 0 | Have the **driver** on the classpath | Dependency (e.g. `org.postgresql:postgresql`) |
| 1 | Get a **connection** | `DriverManager.getConnection(url, user, pw)`, or better `DataSource.getConnection()` |
| 2 | Create a **statement** | `connection.prepareStatement(sql)` |
| 3 | **Bind parameters** | `ps.setString(1, …)`, `ps.setInt(2, …)` |
| 4 | **Execute** | `executeQuery()` (SELECT) / `executeUpdate()` (INSERT/UPDATE/DELETE/DDL) |
| 5 | **Process the results** | Iterate the `ResultSet`, map each row to an object |
| 6 | Manage the **transaction** | `setAutoCommit(false)` → `commit()` / `rollback()` |
| 7 | **Handle errors** | `SQLException` (checked): `getSQLState()`, `getErrorCode()` |
| 8 | **Close** everything | `ResultSet`, `Statement`, `Connection`, with try-with-resources |

### Step 0: the driver

```text
driver for URL: org.h2.Driver
```

Old tutorials start with `Class.forName("org.h2.Driver")`. **That hasn't been needed since JDBC 4
(Java 6):** drivers register themselves through `ServiceLoader`. Verified, there's no `Class.forName`
in the demo.

### Step 1: connection

```java
try (Connection c = DriverManager.getConnection("jdbc:h2:mem:jobfinder", "sa", "")) {
    // connected to H2 2.5.252 (2026-09-23), autoCommit=true
}
```

The **JDBC URL** identifies the database: `jdbc:<vendor>:<location>`, e.g.
`jdbc:postgresql://localhost:5432/jobfinder`. Note that `autoCommit=true` is the default: every
statement is its own transaction (see step 6).

### Steps 2–4: create the table, insert with parameters

```java
try (Statement st = c.createStatement()) {
    st.executeUpdate("CREATE TABLE job (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(200) NOT NULL, "
            + "company VARCHAR(100) NOT NULL, salary INT CHECK (salary > 0))");
}

try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO job (title, company, salary) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
    ps.setString(1, "Java Developer");
    ps.setString(2, "Acme");
    ps.setInt(3, 90000);
    int rows = ps.executeUpdate();
    try (ResultSet keys = ps.getGeneratedKeys()) { keys.next(); long id = keys.getLong(1); }
}
```

```text
inserted 1 row, id=1 (Java Developer)
inserted 1 row, id=2 (Senior Java Engineer)
inserted 1 row, id=3 (Python Developer)
```

`?` placeholders are numbered from **1**, not 0.

### Step 5: query and map rows to objects

```java
public record Job(long id, String title, String company, int salary) {}

try (PreparedStatement ps = c.prepareStatement(
        "SELECT id, title, company, salary FROM job WHERE company = ? ORDER BY id")) {
    ps.setString(1, company);
    try (ResultSet rs = ps.executeQuery()) {
        List<Job> jobs = new ArrayList<>();
        while (rs.next()) {                       // move the cursor row by row
            jobs.add(new Job(rs.getLong("id"), rs.getString("title"),
                             rs.getString("company"), rs.getInt("salary")));
        }
        return jobs;
    }
}
```

```text
Acme jobs: [Job[id=1, title=Java Developer, company=Acme, salary=90000],
            Job[id=2, title=Senior Java Engineer, company=Acme, salary=120000]]
```

---

## 3. Production problem 1: SQL injection (verified)

Building SQL by **string concatenation** with user input:

```java
String company = "Nobody' OR '1'='1";                                  // typed by an attacker
st.executeQuery("SELECT COUNT(*) FROM job WHERE company = '" + company + "'");
// → SELECT ... WHERE company = 'Nobody' OR '1'='1'      (always true!)
```

```text
concatenated SQL  -> 3 rows   <- ALL rows leaked!
PreparedStatement -> 0 rows   (input treated as a value, not SQL)
```

With a `PreparedStatement`, the SQL and the values travel **separately** to the database, so input can
**never** change the query's structure.

**Rule:** never concatenate user input into SQL. Always use parameters (`?` or `:name`). SQL injection
has been one of the most common and damaging web vulnerabilities for decades (OWASP Top 10, "Injection").

---

## 4. Production problem 2: transactions (verified)

A business operation often needs **several** statements to succeed or fail **together**.

```java
c.setAutoCommit(false);                    // start a transaction
try {
    insert("Go Developer", "Initech", 95000);      // succeeds
    insert("Free Internship", "Initech", 0);       // violates CHECK (salary > 0)
    c.commit();
} catch (SQLException e) {
    c.rollback();                          // undo EVERYTHING since the transaction started
} finally {
    c.setAutoCommit(true);
}
```

```text
rows before: 3
2nd insert failed -> rollback. SQLState=23513 errorCode=23513 (Check constraint violation: "CONSTRAINT_11: ")
rows after:  3   <- the 1st insert was undone too
```

Without the transaction, "Go Developer" would have stayed, leaving **half-finished** data. That's the
**A** in ACID: **Atomicity**.

Also note the error details. `SQLState` `23513` is a standardised class of error ("23" = integrity
constraint violation). Spring uses these codes to translate errors (§7).

---

## 5. Production problem 3: leaked connections (verified)

Every open connection uses memory and a session **on the database server**, and databases allow only
a limited number.

```text
open sessions now: 0
after 5 getConnection() without close: 5 open sessions     ← leaked
after 5 with try-with-resources:       0 open sessions     ← always closed
```

A leak is invisible at first. Under load the database runs out of connections and **the whole app
stops**. That's why every `Connection`, `Statement` and `ResultSet` in this note is in
**try-with-resources** (note 01 §10), which closes them even when an exception is thrown.

---

## 6. Production problem 4: opening connections is expensive → connection pools (verified)

A real database connection means a TCP connection, authentication, often a TLS handshake, and a
server-side process. Doing that on **every** request is slow.

A **connection pool** keeps a few connections open and **lends** them out:

```java
var config = new HikariConfig();
config.setJdbcUrl(URL);
config.setUsername("sa");
config.setMaximumPoolSize(5);
try (HikariDataSource ds = new HikariDataSource(config)) {
    try (Connection c = ds.getConnection()) {   // borrowed from the pool
        ...
    }                                           // close() RETURNS it to the pool
}
```

Verified, 2,000 × (get a connection, run a query, close):

```text
DriverManager 151 ms, Hikari pool 13 ms           (≈11× faster)
pooled connection class: HikariProxyConnection (close() returns it to the pool)
physical sessions held by pool: 5 (max pool size 5)
```

This is with an **in-memory** database, where a new connection is cheap. Against PostgreSQL over a
network, each new connection costs far more, so pooling matters much more. **HikariCP is Spring Boot's
default pool**, and Boot creates it automatically from `spring.datasource.*` properties.

**`DataSource` vs `DriverManager`:** production code asks a `DataSource` for connections, never
`DriverManager`. The `DataSource` hides pooling, credentials and configuration.

---

## 7. The same work with Spring's `JdbcClient` (verified)

Plain JDBC needs a lot of boilerplate: try-with-resources everywhere, manual mapping, checked
`SQLException`, and manual commit/rollback. Spring's **`JdbcClient`** (Spring Framework 6.1+) handles
the plumbing:

```java
JdbcClient jdbc = JdbcClient.create(dataSource);

jdbc.sql("INSERT INTO job (title, company, salary) VALUES (:title, :company, :salary)")
    .param("title", "Java Developer").param("company", "Acme").param("salary", 90000)
    .update(keyHolder);                                         // → id 1

List<Job> acme = jdbc.sql("SELECT id, title, company, salary FROM job WHERE company = :company ORDER BY id")
    .param("company", "Acme")
    .query(Job.class)                                           // maps columns → record components
    .list();
```

```text
insert -> id 1
query  -> [Job[id=1, title=Java Developer, ...], Job[id=2, title=Senior Java Engineer, ...]]
error  -> DataIntegrityViolationException (a Spring DataAccessException, unchecked;
          cause: JdbcSQLIntegrityConstraintViolationException)
tx     -> rolled back on DataIntegrityViolationException
count  -> 2 (Go Developer was rolled back)
```

| Concern | Plain JDBC | Spring `JdbcClient` |
|---------|------------|---------------------|
| Open/close resources | You, every time | Spring |
| Parameters | `?` by position | `:name` (or `?`) |
| Row mapping | Manual `rs.getXxx` | `.query(Job.class)` |
| Errors | Checked `SQLException` + vendor codes | Unchecked **`DataAccessException`** hierarchy, the same for every database (e.g. `DataIntegrityViolationException`) |
| Transactions | `setAutoCommit` / `commit` / `rollback` | `TransactionTemplate` here; **`@Transactional`** in a Spring app |

The **exception translation** is what `@Repository` refers to in note 07 §4: Spring turns vendor-specific
`SQLException`s into one consistent, unchecked hierarchy.

---

## 8. The data-access ladder in Spring

```text
More control, more code                                          Less code, more "magic"
 ├── Plain JDBC              (this note)
 ├── JdbcClient / JdbcTemplate   you write SQL, Spring does the plumbing
 ├── Spring Data JDBC        repositories for simple aggregates, no lazy loading
 ├── JPA / Hibernate         object-relational mapping: entities, relationships, caching
 └── Spring Data JPA         interfaces like JobRepository extends JpaRepository<Job, Long>
```

**For Job Finder (🔜):** PostgreSQL (driver 42.7.14) with Spring Data JPA for the domain model (it fits
the architecture in note 19, where `JobRepository` is a domain interface), **Flyway** (12.11.0) for
versioned schema migrations, and **Testcontainers** to test against a real PostgreSQL. `JdbcClient`
stays available for reporting and search queries where hand-written SQL is clearer. All versions are
managed by Boot 4.2. This will be recorded as an ADR when persistence is added.

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| Concatenating input into SQL | SQL injection (verified: all rows leaked) |
| Not closing connections | Connection leaks → database runs out of sessions (verified) |
| `DriverManager` in application code | A new physical connection per request; use a pooled `DataSource` |
| Multi-step changes without a transaction | Half-written data on failure (verified the fix) |
| `Class.forName(driver)` | Unnecessary since JDBC 4 |
| `SELECT *` | Breaks when columns change; fetches unneeded data |
| Swallowing `SQLException` | Silent data problems; let it propagate or translate it |
| Credentials hard-coded in code | Use externalised config and secrets (🔜) |

## Interview questions

**Q: What are the steps to query a database with JDBC?**
Get a connection (from a `DataSource`), create a `PreparedStatement`, bind parameters, execute, iterate
the `ResultSet` and map rows, handle `SQLException`, manage the transaction, and close all resources
(try-with-resources).

**Q: `Statement` vs `PreparedStatement`?**
`PreparedStatement` sends SQL and parameters separately, which prevents SQL injection, handles types and
escaping, and can be reused efficiently. Use it for any SQL with input.

**Q: Why use a connection pool?**
Creating physical connections is expensive. A pool reuses a bounded set of open connections, which improves
latency and protects the database from too many connections. HikariCP is Spring Boot's default.

**Q: What does `autoCommit` mean?**
When `true` (the default), each statement is committed immediately. Set it to `false` to group statements
into one transaction, then `commit()` or `rollback()`.

**Q: What is Spring's `DataAccessException`?**
An unchecked, database-independent exception hierarchy that Spring translates `SQLException`s into, e.g.
`DataIntegrityViolationException`.

**Q: Do you still need `Class.forName` to load a JDBC driver?**
No. Since JDBC 4, drivers are discovered automatically via `ServiceLoader`.
