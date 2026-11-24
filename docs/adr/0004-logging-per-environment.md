# ADR 0004: Human-readable logs in development, ECS JSON logs in production

- **Status:** Accepted; extended by [ADR 0013](0013-request-correlation.md) (trace ID in every log line; ECS `trace.id`)
- **Date:** 2026-11-20

## Context

Logs serve two different readers:

- **Developers** at a terminal want short, colored, readable lines, with our own code at DEBUG.
- **Production log platforms** (Elastic, Grafana Loki, Datadog, CloudWatch) need machine-readable
  records: one event per line, fields they can index (level, logger, service, environment),
  exceptions as fields rather than 50 separate lines, and context such as request IDs as fields.

Spring Boot 4 supports structured logging natively (`logging.structured.format.console`: `ecs`,
`logstash`, `gelf`). Applications run in containers, where logs go to **stdout** and the platform
collects them (Twelve-Factor App, "Logs").

## Decision

Configure logging per environment with Spring profiles:

| Profile | Console format | Our code (`com.shahabuddin.jobfinder`) | Activated by |
|---------|----------------|----------------------------------------|--------------|
| *(none)* | Spring Boot default pattern | INFO | Default |
| `dev` | Compact colored pattern (`HH:mm:ss.SSS LEVEL [thread] logger : message`) | DEBUG | `mvn spring-boot:run` (plugin config) |
| `prod` | **ECS JSON** (Elastic Common Schema), `service.environment=prod` | INFO | `SPRING_PROFILES_ACTIVE=prod` |

- Logs go to **stdout only**, with no log files in the application. Collection, retention and
  rotation are the platform's job.
- **ECS** is chosen over Logstash/GELF because it's a vendor-neutral, documented schema (contributed
  to OpenTelemetry and being merged into its semantic conventions) and nests fields (`service.*`,
  `log.*`, `error.*`).
- `dev` is never active in the packaged jar by default. Production must opt in to `prod` explicitly,
  so a missing profile falls back to safe defaults (INFO, details hidden).
- Code logs through SLF4J only. `System.out` and `java.util.logging` are forbidden by ArchUnit rules
  (ADR 0001).

## Consequences

- Readable local output, and queryable production logs with exceptions in `error.type`,
  `error.message` and `error.stack_trace`.
- MDC values (e.g. a future request/correlation ID) automatically become JSON fields.
- Locked in by `ProfileConfigurationTests`, which starts the real application in a separate JVM per
  profile (logging format is initialized once per JVM, so in-JVM tests can't verify it reliably) and
  also checks that health details are visible in `dev` and hidden in `prod`.
- Trade-off: raw production logs are harder to read by eye. Use the log platform, or `jq`, to read them.

## References

- Learning note: `learning/20-spring-boot-logging.md`
