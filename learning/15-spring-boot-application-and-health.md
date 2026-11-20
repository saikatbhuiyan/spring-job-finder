# 15 — `@SpringBootApplication`, Bootstrapping Spring Boot, and the Health Endpoint

## Goal

Move the project from **plain Spring** (notes 02–14) to **Spring Boot**, understand exactly what
`@SpringBootApplication` does, and expose a production-style **health endpoint** with Spring
Boot Actuator. Everything below was run and verified on **Spring Boot 4.2.0** (Spring
Framework 7.1.0, Java 25, Tomcat 11.0.27).

Status: ✅ **applied**. This is the project's current code.

---

## 1. Why Spring Boot?

In notes 02–14 we did everything by hand: created the `ApplicationContext`, listed config
classes, added `jakarta.annotation-api` ourselves, and chose every library version. A real web
app would also need an HTTP server, JSON conversion, logging and health checks, which is a lot of
setup.

**Spring Boot is Spring Framework + sensible defaults.** It doesn't replace anything you learned.
It:

| Boot feature | What it does for us |
|--------------|---------------------|
| **Starters** | One dependency pulls in a tested set of libraries (`spring-boot-starter-webmvc` = Spring MVC + Tomcat + Jackson) |
| **Dependency management** | The parent POM picks compatible versions, so we write no `<version>` tags |
| **Auto-configuration** | Creates beans automatically based on what's on the classpath |
| **Embedded server** | Tomcat runs *inside* the app; no separate server install |
| **Externalised config** | `application.properties` instead of code |
| **Actuator** | Production endpoints: health, metrics, info |
| **Executable jar** | `java -jar app.jar` runs everything |

---

## 2. `@SpringBootApplication` explained

[JobFinderApplication.java](../src/main/java/com/shahabuddin/jobfinder/JobFinderApplication.java):

```java
package com.shahabuddin.jobfinder;

@SpringBootApplication
public class JobFinderApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobFinderApplication.class, args);
    }
}
```

### It's three annotations in one (verified by reflection)

```text
@SpringBootApplication    -> @SpringBootConfiguration
@SpringBootApplication    -> @EnableAutoConfiguration
@SpringBootApplication    -> @ComponentScan
@SpringBootConfiguration  -> @Configuration
@EnableAutoConfiguration  -> @AutoConfigurationPackage
@EnableAutoConfiguration  -> @Import(AutoConfigurationImportSelector.class)
```

Everything here is something you already know:

| Part | What it does | Learned in |
|------|--------------|------------|
| **`@SpringBootConfiguration`** | It's a `@Configuration` class, so you can add `@Bean` methods here. Boot (and tests) use it to *find* the main configuration. | Notes 02, 07 |
| **`@ComponentScan`** | Scans **this class's package and all sub-packages** for `@Component`, `@Service`, `@Repository`, `@RestController`… | Notes 04, 07 |
| **`@EnableAutoConfiguration`** | **`@Import(AutoConfigurationImportSelector)`**: an `ImportSelector` that picks which auto-configuration classes to import, based on the classpath and your properties | Note 06 §5–§6 |

So `@SpringBootApplication` is: "this is a config class, scan from here downwards, and import
whatever auto-configuration fits".

### ⚠️ Why the main class lives in the root package

`@ComponentScan` without arguments scans **the package of the annotated class** and everything
below it:

```text
com.shahabuddin.jobfinder              ← JobFinderApplication (scan starts here)
├── job/        JobController, JobService...      ✅ scanned
├── company/    ...                               ✅ scanned
└── common/     ...                               ✅ scanned
com.shahabuddin.other                             ❌ NOT scanned (sibling, not below)
```

This is the same trap as note 04 §4 (wrong scan package): a class outside the tree is silently
ignored. That's why the app class sits at the **root** of the feature packages from note 05 §5.

### What `SpringApplication.run(...)` does

1. Creates the right `ApplicationContext` (a **web** context here, because Spring MVC is on the
   classpath).
2. Loads `application.properties` into the `Environment`.
3. Registers `JobFinderApplication` as the main configuration, then scans and imports the
   auto-configuration.
4. Calls `refresh()`, which creates singletons eagerly (note 14) and **starts embedded Tomcat**.
5. Returns the running context. The app keeps running until stopped (Ctrl+C runs the destroy
   callbacks, note 04).

Compare with plain Spring:

```java
// Before (notes 02–14)
try (var context = new AnnotationConfigApplicationContext(ProjectConfig.class)) { ... }

// Now
SpringApplication.run(JobFinderApplication.class, args);
```

---

## 3. Auto-configuration: how Boot "knows" what to set up

Each auto-configuration class is an ordinary `@Configuration` class, guarded by **conditions**
such as:
- `@ConditionalOnClass(DispatcherServlet.class)`: only if Spring MVC is on the classpath;
- `@ConditionalOnMissingBean`: only if **you** haven't defined that bean yourself.

The second rule means **your beans always win**. Define your own and Boot backs off.

Running with `--debug` prints the **condition evaluation report**. For this app I counted
**51 positive** and **64 negative** condition entries. For example:

```text
DispatcherServletAutoConfiguration matched:     ← Spring MVC found → DispatcherServlet created
```

There's no database auto-configuration in the report at all, because no database library is on
the classpath yet. Add `spring-boot-starter-data-jpa` later and the related auto-configurations
appear automatically.

---

## 4. The new `pom.xml`

[pom.xml](../pom.xml):

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.2.0</version>
</parent>

<properties>
    <java.version>25</java.version>
</properties>

<dependencies>
    <dependency>  <!-- Spring MVC + embedded Tomcat + Jackson -->
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>  <!-- /actuator/health and friends -->
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- test: spring-boot-starter-webmvc-test, spring-boot-starter-actuator-test -->
</dependencies>

<build><plugins>
    <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
</plugins></build>
```

Notes:

- **The parent** supplies versions for hundreds of libraries, so no `<version>` tags are needed.
  It manages Spring Framework **7.1.0** (we had 7.0.10 by hand before), and `jakarta.annotation-api`
  **3.0.0** now arrives automatically through the starters (verified with `mvn dependency:tree`),
  so the manual dependency from note 04 is gone.
- **The parent also turns on the `-parameters` compiler flag.** That's why the parameter-name
  matching that failed in notes 03 §8 and 09 §3 would now work. Still prefer `@Qualifier`.
- **Spring Boot 4 starter names:** `spring-boot-starter-web` still exists but is **deprecated in
  favor of `spring-boot-starter-webmvc`** (verified in its POM description). Boot 4 also pairs each
  starter with a matching `-test` starter.
- **Why 4.2.0:** 4.2.0 (Framework 7.1) and 4.1.3 (Framework 7.0.11) were both released on
  2026-11-19. A new project should start on the newest release line, which has the longest
  support ahead of it.

### What happened to the Spring Core demo code?

`Main`, `Vehicle` and `ProjectConfig` were removed. They're preserved at the git tag
**[`spring-core-basics`](https://github.com/saikatbhuiyan/spring-job-finder/tree/spring-core-basics)**,
and notes 00–04 link there.

---

## 5. Spring Boot Actuator and the health endpoint

**Actuator** adds production-ready HTTP endpoints for **monitoring** a running app. The most
important is **health**: "is this app OK?"

### Configuration

[application.properties](../src/main/resources/application.properties):

```properties
spring.application.name=job-finder

# Only expose /actuator/health over HTTP (also Boot's default; explicit on purpose)
management.endpoints.web.exposure.include=health

# Add /actuator/health/liveness and /actuator/health/readiness
management.endpoint.health.probes.enabled=true

# Never show internal details to callers (the default)
management.endpoint.health.show-details=never
```

### The endpoints (verified with `curl` against the running jar)

```text
GET /actuator/health            → 200 {"groups":["liveness","readiness"],"status":"UP"}
GET /actuator/health/liveness   → 200 {"status":"UP"}
GET /actuator/health/readiness  → 200 {"status":"UP"}
GET /actuator                   → 200 links to the exposed endpoints (only health)
GET /actuator/env               → 404 (not exposed: it would leak configuration)
GET /                           → 404 (no controllers yet)
```

### Liveness vs readiness

| Probe | Question it answers | If it fails, the platform… |
|-------|---------------------|-----------------------------|
| **Liveness** | "Is the app alive, or stuck and needing a restart?" | **Restarts** the container |
| **Readiness** | "Can the app take traffic right now?" (e.g. still starting, or its DB is down) | **Stops sending requests** until it's ready again |
| **`/actuator/health`** | Overall status, combining the health indicators | Used by load balancers and monitoring |

Docker health checks and Kubernetes probes will call these URLs when we containerise the app
(🔜 roadmap). On Kubernetes, Boot enables probes automatically. The property enables them
everywhere, including locally.

### Why details are hidden

With `show-details=always` (tried once via `--management.endpoint.health.show-details=always`):

```json
{
  "components": {
    "diskSpace":      { "status": "UP", "details": { "total": 1995218165760, "free": 1252342534144, "threshold": 10485760, "path": "...", "exists": true } },
    "livenessState":  { "status": "UP" },
    "ping":           { "status": "UP" },
    "readinessState": { "status": "UP" },
    "ssl":            { "status": "UP", "details": { "expiringChains": [], "invalidChains": [], "validChains": [] } }
  },
  "groups": ["liveness", "readiness"],
  "status": "UP"
}
```

This reveals disk sizes and file paths, and later database type and version. That's useful to
attackers, so **public callers get only `status`**. Once Spring Security is added,
`show-details=when-authorized` can reveal details to admins only (🔜).

**Health indicators** are beans (`diskSpace`, `ping`, …) that Boot auto-configures. Adding a
database later automatically adds a `db` indicator, and the overall status becomes `DOWN` if the
database is unreachable.

---

## 6. Run it

```bash
# Development: compile and run
mvn spring-boot:run

# Or build the executable jar (≈22 MB, includes Tomcat) and run it
mvn clean package
java -jar target/job-finder-0.0.1-SNAPSHOT.jar

# Check it
curl localhost:8080/actuator/health
```

Startup log (verified):

```text
Starting JobFinderApplication v0.0.1-SNAPSHOT using Java 25.0.1
Exposing 1 endpoint beneath base path '/actuator'
Tomcat started on port 8080 (http) with context path '/'
Started JobFinderApplication in 1.302 seconds
```

Any property can be overridden at launch, e.g. `java -jar app.jar --server.port=9090`.

---

## 7. Tests

[HealthEndpointTests.java](../src/test/java/com/shahabuddin/jobfinder/HealthEndpointTests.java)
starts the **whole app on a random port** and calls it over real HTTP:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class HealthEndpointTests {

    @Autowired private RestTestClient client;

    @Test
    void healthIsUp() {
        client.get().uri("/actuator/health")
              .exchange()
              .expectStatus().isOk()
              .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
    // + liveness, readiness, details hidden, /actuator/env not exposed
}
```

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

- `@SpringBootTest` finds `JobFinderApplication` through its `@SpringBootConfiguration` (seen in
  the log: `Found @SpringBootConfiguration ... JobFinderApplication`).
- `RANDOM_PORT` avoids clashing with an app already running on 8080 (`Tomcat initialized with port 0`).
- `RestTestClient` is Spring Framework 7's test HTTP client. In Boot 4 it's enabled by
  `@AutoConfigureRestTestClient` (from `spring-boot-resttestclient`).
- The security-relevant behavior (details hidden, `/actuator/env` not exposed) is **tested**, so a
  future config change can't silently leak information.

Run: `mvn test`.

> A harmless warning appears during tests: *"Mockito is currently self-attaching…"*. It comes from
> the test libraries (we don't use Mockito yet). It can be silenced later by configuring Mockito as
> a Java agent in the Surefire plugin.

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| Main class in a sub-package (e.g. `jobfinder.config`) | Components in sibling packages aren't scanned |
| Adding `<version>` to Boot-managed dependencies | Can break the tested version set; let the parent decide |
| `spring-boot-starter-web` in Boot 4 | Works but deprecated; use `spring-boot-starter-webmvc` |
| `management.endpoints.web.exposure.include=*` | Exposes `env`, `beans`, `heapdump`… and leaks secrets |
| `show-details=always` in production | Leaks infrastructure details |
| Defining a bean Boot also auto-configures, then wondering why Boot's is gone | `@ConditionalOnMissingBean`: yours replaces Boot's (this is intended) |

## Interview questions

**Q: What does `@SpringBootApplication` do?**
It combines `@SpringBootConfiguration` (a `@Configuration`), `@EnableAutoConfiguration`, and
`@ComponentScan` of the class's package and sub-packages.

**Q: How does auto-configuration work?**
`@EnableAutoConfiguration` imports `AutoConfigurationImportSelector`, which loads candidate
auto-configuration classes. Each is a `@Configuration` guarded by conditions like
`@ConditionalOnClass` and `@ConditionalOnMissingBean`.

**Q: How do you override an auto-configured bean?**
Define your own bean of that type. Auto-configurations back off because of `@ConditionalOnMissingBean`.

**Q: Why should the main class be in the root package?**
Component scanning starts at its package; classes outside that tree aren't found.

**Q: What is Spring Boot Actuator?**
A module adding production endpoints (health, metrics, info…) for monitoring and management.

**Q: Liveness vs readiness?**
Liveness: is the app alive (restart it if not). Readiness: can it accept traffic right now (stop
routing requests to it if not).

**Q: Which actuator endpoints are exposed over HTTP by default?**
Only `health`. Exposing others must be a deliberate choice, because some leak sensitive data.

**Q: How do you see what auto-configuration did?**
Run with `--debug` to print the condition evaluation report.
