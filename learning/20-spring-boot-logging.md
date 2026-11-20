# 20 — Spring Boot Logging: Console Patterns, Profiles, and Structured JSON

## Goal

Learn how Spring Boot logging works, how to configure the **console logging pattern**, and how a
production app logs differently in **development** (readable lines) and **production**
(structured JSON for log platforms). Everything was verified on Spring Boot 4.2.0 (Logback). The
setup is applied to Job Finder and tested.

Decision record: [ADR 0004](../docs/adr/0004-logging-per-environment.md).

Status: ✅ **applied**. `dev` and `prod` profiles configured, verified by running the real jar,
and locked in by tests.

---

## 1. How logging fits together

```text
Your code ──▶ SLF4J (API) ──▶ Logback (implementation) ──▶ Appender ──▶ console (stdout)
               LoggerFactory     configured by Spring Boot     pattern or JSON
```

- **SLF4J** is the *API* you call. **Logback** is the *engine* that writes the output (Spring Boot's
  default, via `spring-boot-starter-logging`).
- You almost never write Logback XML. Spring Boot configures it from `application.properties`.

### Writing log statements correctly

```java
private static final Logger log = LoggerFactory.getLogger(JobService.class);

log.debug("searching jobs keyword={}", keyword);          // ✅ {} placeholders
log.info("job {} posted by company {}", jobId, companyId);
log.error("could not parse page size", exception);         // ✅ exception as LAST argument

log.info("job " + jobId + " posted");                      // ❌ builds the string even if INFO is off
log.error("failed: " + e.getMessage());                    // ❌ loses the stack trace
System.out.println("job posted");                          // ❌ forbidden by our ArchUnit rule (note 19)
```

### Levels (lowest → highest)

| Level | Use for | Example |
|-------|---------|---------|
| `TRACE` | Very fine detail, rarely enabled | Each loop iteration |
| `DEBUG` | Developer diagnostics | "searching jobs keyword=java" |
| `INFO` | Normal, important events | "job 42 posted", "Started JobFinderApplication" |
| `WARN` | Something unexpected but handled | "salary missing for job 42" |
| `ERROR` | A failure that needs attention | Exception while saving a job |

Setting a level shows that level **and everything above it**. Boot's default is `INFO`, so the
demo's `trace` and `debug` lines didn't appear until the level was lowered (verified).

---

## 2. The default console pattern (verified)

```text
2026-11-20T22:08:46.615+06:00  INFO 10411 --- [job-finder] [           main] lg.LogDemo                               : info: job 42 posted by company 7
└─────── timestamp ─────────┘ level  PID      app name     └── thread ──┘ └──────── logger ───────────────┘   message
```

It comes from Boot's `defaults.xml` (read from the 4.2.0 jar):

```text
%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} %clr(%5p) %clr(${PID}){magenta}
%clr(--- %esb(){APPLICATION_NAME}%esb{APPLICATION_GROUP}[%15.15t] ${LOG_CORRELATION_PATTERN}){faint}
%clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n%wEx
```

Exceptions print the full stack trace on the following lines (verified):

```text
... ERROR ... lg.LogDemo : error: could not parse page size
java.lang.NumberFormatException: For input string: "abc"
	at java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java:67) ~[na:na]
	...
```

---

## 3. Configuring the console pattern

### 3.1 Replace the whole pattern: `logging.pattern.console`

```properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%thread] %logger{20} [requestId=%X{requestId:-none}] - %msg%n
logging.level.lg=DEBUG
```

Verified output:

```text
22:08:48.077 DEBUG [main] lg.LogDemo [requestId=req-7f3a] - debug: searching jobs keyword=java
22:08:48.077 INFO  [main] lg.LogDemo [requestId=req-7f3a] - info: job 42 posted by company 7
22:08:48.077 WARN  [main] lg.LogDemo [requestId=req-7f3a] - warn: salary missing for job 42
22:08:48.077 ERROR [main] lg.LogDemo [requestId=req-7f3a] - error: could not parse page size
```

(Startup lines logged before `MDC.put` showed `requestId=none`, the `:-none` default.)

### 3.2 Pattern conversion words

| Word | Meaning | Example output |
|------|---------|----------------|
| `%d{HH:mm:ss.SSS}` | Timestamp, any `DateTimeFormatter`-style pattern | `22:08:48.077` |
| `%level` / `%p` | Level | `INFO` |
| `%-5level` / `%5p` | Padded to 5: left- / right-aligned | `INFO ` / ` INFO` |
| `%thread` / `%t` | Thread name | `main` |
| `%15.15t` | Pad to 15, truncate to 15 | `           main` |
| `%logger{20}` | Logger name, abbreviated to ~20 chars | `c.s.jobfinder.JobService` |
| `%-40.40logger{39}` | Pad/truncate to a fixed width (aligned columns) | |
| `%msg` / `%m` | The message | |
| `%n` | Newline | |
| `%X{key}` / `%X{key:-default}` | **MDC** value (per-request context, §5) | `req-7f3a` |
| `%wEx` | Exception with stack trace (Boot's converter) | |
| `%clr(...){color}` | Boot's ANSI color helper (`faint`, `cyan`, `magenta`, …) | colors in the terminal |
| `${PID}` | Process ID | `10411` |

### 3.3 Change only parts of the default pattern

Rather than replacing everything:

```properties
logging.pattern.dateformat=HH:mm:ss                  # just the date part
logging.pattern.level=%5p [%X{requestId:-}]          # just the level part (here + MDC)
```

Verified:

```text
22:08:49  INFO [req-7f3a] 10490 --- [job-finder] [           main] lg.LogDemo : info: job 42 posted by company 7
```

### 3.4 Other useful console settings

| Property | Effect | Verified |
|----------|--------|----------|
| `logging.level.<package>=DEBUG` | Level per package/class | ✅ |
| `logging.threshold.console=WARN` | The console only shows WARN+ (loggers may still log elsewhere) | ✅ only WARN/ERROR printed |
| `spring.output.ansi.enabled=always\|never\|detect` | Colors (`detect` = only in a real terminal) | |
| `logging.include-application-name=false` | Drop `[job-finder]` from lines | |

---

## 4. Structured (JSON) logging: the production way

In production nobody reads logs in a terminal. They're shipped to a **log platform** that indexes
them. Platforms need **one event per line** with **named fields**. Spring Boot 4 has this
built in, with no extra library:

```properties
logging.structured.format.console=ecs        # or logstash, or gelf
```

### Three formats, verified

**ECS (Elastic Common Schema):**

```json
{"@timestamp":"2026-11-20T16:08:51.008004Z","log":{"level":"INFO","logger":"lg.LogDemo"},"process":{"pid":10569,"thread":{"name":"main"}},"service":{"name":"job-finder"},"message":"info: job 42 posted by company 7","requestId":"req-7f3a","ecs":{"version":"8.11"}}
```

**Logstash:**

```json
{"@timestamp":"2026-11-20T22:08:51.490378+06:00","@version":"1","message":"...","logger_name":"lg.LogDemo","thread_name":"main","level":"INFO","level_value":20000}
```

**GELF (Graylog):**

```json
{"version":"1.1","short_message":"...","timestamp":1795190932.947,"level":6,"_level_name":"INFO","_process_pid":10649,"host":"job-finder","_log_logger":"lg.LogDemo"}
```

### Exceptions in JSON: the biggest win (verified)

```json
{
  "@timestamp": "2026-11-20T16:09:28.507146Z",
  "log": { "level": "ERROR", "logger": "lg.LogDemo" },
  "service": { "name": "job-finder", "environment": "prod" },
  "message": "error: could not parse page size",
  "requestId": "req-7f3a",
  "error": {
    "type": "java.lang.NumberFormatException",
    "message": "For input string: \"abc\"",
    "stack_trace": "java.lang.NumberFormatException: For input string: \"abc\"\n\tat java.base/..."
  },
  "ecs": { "version": "8.11" }
}
```

That's **one line** in the real output (verified: one line, not 50). With plain text, a stack trace
is dozens of separate lines that get split up and interleaved with other requests' logs. With JSON
you can query *"all errors where `error.type` = NumberFormatException in service job-finder, prod,
last hour"*.

### Which format?

| Format | Ecosystem | Job Finder |
|--------|-----------|------------|
| **ECS** | Elastic; vendor-neutral schema, contributed to OpenTelemetry | ✅ chosen (ADR 0004) |
| Logstash | Logstash / ELK pipelines | |
| GELF | Graylog | |

---

## 5. MDC: context on every line

**MDC (Mapped Diagnostic Context)** is a per-thread map of key → value that the logger attaches
to every line logged on that thread:

```java
MDC.put("requestId", "req-7f3a");
log.info("job {} posted", 42);      // → includes requestId=req-7f3a
MDC.clear();                         // always clear: threads are reused across requests (note 13 §10)
```

- In a pattern: `%X{requestId}`.
- In ECS JSON: it becomes a **field automatically** (`"requestId":"req-7f3a"`, verified).

**Next step (🔜):** a servlet filter that puts a **request/correlation ID** into the MDC for every HTTP
request (taken from an incoming `X-Request-Id` header or generated) and returns it in the response.
Then every log line from one request can be found with a single search.

---

## 6. ✅ Applied to Job Finder: profiles per environment

A **Spring profile** is a named set of configuration. `application-{profile}.properties` is loaded
**on top of** `application.properties` when that profile is active, and overrides the same keys.

| File | Contents |
|------|----------|
| [application.properties](../src/main/resources/application.properties) | Shared defaults (Actuator, Problem Details, versioning, details hidden) |
| [application-dev.properties](../src/main/resources/application-dev.properties) | Compact colored pattern, our code at DEBUG, health details shown |
| [application-prod.properties](../src/main/resources/application-prod.properties) | ECS JSON, `service.environment=prod`, root INFO |

```properties
# application-dev.properties
logging.pattern.console=%clr(%d{HH:mm:ss.SSS}){faint} %clr(%5p) %clr([%-15.15t]){faint} %clr(%-36.36logger{35}){cyan} %clr(:){faint} %m%n%wEx
logging.level.com.shahabuddin.jobfinder=DEBUG
management.endpoint.health.show-details=always

# application-prod.properties
logging.structured.format.console=ecs
logging.structured.ecs.service.environment=prod
logging.level.root=INFO
```

### How each profile is activated (verified by running the real app)

```text
mvn spring-boot:run                       → dev  (spring-boot-maven-plugin <profiles>dev</profiles>)
  22:11:37.982 DEBUG [main           ] c.s.jobfinder.JobFinderApplication   : Running with Spring Boot v4.2.0, Spring v7.1.0
  22:11:37.985  INFO [main           ] c.s.jobfinder.JobFinderApplication   : The following 1 profile is active: "dev"

java -jar target/job-finder-0.0.1-SNAPSHOT.jar   → no profile, Boot's default pattern
  2026-11-20T22:12:40.221+06:00  INFO 24055 --- [job-finder] [main] ... : No active profile set, falling back to 1 default profile: "default"

SPRING_PROFILES_ACTIVE=prod java -jar target/job-finder-0.0.1-SNAPSHOT.jar   → ECS JSON
  {..."service":{"name":"job-finder","version":"0.0.1-SNAPSHOT","environment":"prod"},"message":"Started JobFinderApplication in 1.206 seconds ..."}
```

(`service.version` comes from the jar's manifest automatically.)

**Why `dev` is only switched on for `spring-boot:run`:** the packaged jar is what gets deployed. If
someone forgets to set a profile in production, it falls back to **safe** defaults (INFO, health
details hidden), never to dev's DEBUG and visible details. Production opts in explicitly with
`SPRING_PROFILES_ACTIVE=prod` (an environment variable, how containers and Kubernetes pass config).

### Why logs go to stdout only

No `logging.file.name`. In containers, the app writes to **stdout** and the platform (Docker,
Kubernetes, a cloud provider) collects, rotates and ships the logs (Twelve-Factor App, "Logs").
Log files inside containers fill disks and disappear when the container dies.

---

## 7. Testing the logging setup (and a trap)

### First attempt: `@SpringBootTest` per profile, and it failed

Two nested `@SpringBootTest` classes, one `@ActiveProfiles("prod")` and one `@ActiveProfiles("dev")`.
The **level** assertions passed, but the **format** assertions failed: the dev test got Boot's
*default* pattern, and the prod test got a non-JSON line.

**Root cause (verified):** each test passed when run **alone** in a fresh JVM. Spring Boot's
`LogbackLoggingSystem` has an `isAlreadyInitialized` check (confirmed in the 4.2.0 class): the
logging **format** is initialized **once per JVM**, while **levels** are re-applied for every new
application context. Maven runs all test classes in one JVM, so the first context's format sticks.
Production runs one app per JVM, so it's a **test-only** effect, but it means in-JVM tests can't
verify logging format.

### The fix: test the real app in a separate JVM

[ProfileConfigurationTests.java](../src/test/java/com/shahabuddin/jobfinder/ProfileConfigurationTests.java)
starts `JobFinderApplication` in a **child JVM** per profile (`--server.port=0`), reads its console
until `Started JobFinderApplication`, reads the port from `Tomcat started on port N`, calls
`/actuator/health`, and then stops the process:

| Test | Asserts |
|------|---------|
| `prodWritesEcsJsonHidesHealthDetailsAndHasNoDebug` | **Every** line parses as JSON with `service.name=job-finder`, `service.environment=prod` and `@timestamp`; no DEBUG startup line; health has **no** `components` |
| `devWritesCompactLinesWithDebugAndShowsHealthDetails` | Lines match the compact pattern; the DEBUG startup line is present; health **has** `components` |

**Mutation check:** I commented out `logging.structured.format.console=ecs` and the prod test
**failed**, then I restored it. The test really guards the behavior.

> One manual check was misleading along the way: a helper script reused an old log file that
> already contained "Started JobFinderApplication", so it called the app before it was ready. A
> clean re-run with the same command was correct. Lesson: start every verification with fresh
> output files, and prefer automated tests like the one above.

### Full suite

```text
HealthEndpointTests 5 · ErrorResponseTests 1 · ApiVersioningTests 3 · ProfileConfigurationTests 2
ArchitectureTests 7 · ArchitectureRulesTests 8 · ModularityTests 2 · ModularityRulesTests 2
Total 30, Failures 0
```

---

## 8. What NOT to log (production rules)

| Never log | Why |
|-----------|-----|
| Passwords, tokens, API keys, `Authorization` headers | Logs are widely readable and kept for a long time |
| Full personal data (emails, phone numbers, CVs) | Privacy laws (GDPR etc.); log IDs instead |
| Whole request/response bodies by default | Size, cost, sensitive data |
| The same exception at every layer | Log it **once**, where it's handled |

And:
- Log **events with IDs** (`job 42 posted by company 7`), not vague text ("done").
- Don't log in tight loops at INFO. Use DEBUG or aggregate.
- Don't log *and* rethrow the same exception (duplicate stack traces).

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| String concatenation in log calls | Wasted work when the level is off |
| `log.error("..." + e.getMessage())` | Stack trace lost; pass `e` as the last argument |
| `System.out.println` | Bypasses levels, format and JSON (ArchUnit forbids it) |
| Plain-text logs in production | Stack traces split into many lines; nothing queryable |
| DEBUG everywhere in production | Noise, cost, possible data leaks |
| Writing log files inside containers | Lost on restart, fills disks |
| Forgetting `MDC.clear()` | The next request on that thread inherits stale context |
| Verifying logging format with in-JVM `@SpringBootTest` | Format is set once per JVM; test in a separate process |

## Interview questions

**Q: SLF4J vs Logback?**
SLF4J is the logging API (facade) your code calls; Logback is the implementation that formats and
writes. Spring Boot uses Logback by default.

**Q: How do you change the console log format in Spring Boot?**
`logging.pattern.console` (whole pattern), or parts via `logging.pattern.dateformat` /
`logging.pattern.level`. Full control is possible with `logback-spring.xml`.

**Q: What is structured logging and why use it in production?**
Logging events as machine-readable records (JSON) with named fields, so log platforms can index,
search and alert on them; exceptions and context become fields. Spring Boot supports ECS, Logstash
and GELF via `logging.structured.format.console`.

**Q: What is MDC?**
Mapped Diagnostic Context: a per-thread key/value map automatically added to log lines, e.g. a
request ID, so all logs of one request can be correlated.

**Q: How do you configure different logging per environment?**
Spring profiles: `application-dev.properties` / `application-prod.properties`, activated with
`spring.profiles.active` or `SPRING_PROFILES_ACTIVE`.

**Q: Where should a containerised app write logs?**
To stdout/stderr. The platform collects and ships them (Twelve-Factor).
