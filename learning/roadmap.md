# Roadmap: From Spring Core to the Job Finder App

This is the planned learning path. Each item becomes its own numbered note once it is
learned or applied, and its status changes in the [README](README.md).

Legend: ✅ applied · 🧪 learned, not applied · 🔜 planned

## Phase 1: Spring Core (plain Spring Framework)

| Status | Topic | Key ideas |
|--------|-------|-----------|
| ✅ | IoC container, `@Configuration`, `@Bean`, `getBean` | [Note 02](02-spring-ioc-and-beans.md) |
| ✅ | Multiple beans of the same type, `@Bean("name")`, aliases, `@Description` | [Note 03](03-multiple-beans-and-naming.md) |
| 🧪 | `@Primary`, `@Qualifier`, `@Fallback`, `List`/`Map` injection | [Note 03 §8](03-multiple-beans-and-naming.md), [note 11](11-primary-vs-qualifier.md); will be ✅ once injection is in the code |
| ✅ | `@Component`, `@ComponentScan` | [Note 04](04-component-scan-and-bean-lifecycle.md) |
| 🧪 | Stereotypes in depth: `@Service`, `@Repository`, `@Controller`, `@RestController`, custom stereotypes | [Note 07](07-stereotype-annotations.md) |
| 🧪 | Dependency injection: constructor (preferred), setter, field | [Note 08](08-bean-wiring.md); becomes ✅ when a real dependency is wired in the code |
| 🧪 | Autowiring in depth: resolution order, collections, generics | [Note 09](09-autowiring.md) |
| 🧪 | Circular dependencies: causes and design fixes | [Note 10](10-circular-dependencies.md) |
| 🧪 | Bean scopes: all six (singleton ✅ in use) | [Note 13](13-bean-scopes.md) |
| ✅ | Bean lifecycle: `InitializingBean`/`DisposableBean` (applied), `@PostConstruct`/`@PreDestroy` (🧪 learned) | [Note 04](04-component-scan-and-bean-lifecycle.md) |
| 🧪 | Modular configuration with `@Import` | [Note 06](06-import-annotation.md) |
| 🧪 | Programmatic bean registration (`BeanRegistrar`, `registerBean`) | [Note 12](12-programmatic-bean-registration.md) |
| 🧪 | Eager vs lazy initialization: `@Lazy` | [Note 14](14-eager-and-lazy-instantiation.md) |
| 🔜 | Externalised configuration: `@PropertySource`, `@Value` | Keeping settings out of code |
| 🔜 | AOP basics: `@Aspect`, `@Before`, `@Around` | Cross-cutting concerns such as logging and timing |
| 🔜 | Unit testing with JUnit 5 | `mvn test`, assertions |

## Phase 2: Spring Boot

| Status | Topic |
|--------|-------|
| ✅ | Migrated to Spring Boot 4.2 (starters, `@SpringBootApplication`, auto-configuration): [note 15](15-spring-boot-application-and-health.md) |
| ✅ | Health endpoint with Actuator (liveness/readiness probes): [note 15 §5](15-spring-boot-application-and-health.md) |
| ✅ | Profiles `dev` / `prod` (`application-{profile}.properties`): [note 20 §6](20-spring-boot-logging.md) |
| ✅ | Logging: console patterns, structured ECS JSON in prod: [note 20](20-spring-boot-logging.md) |
| ✅ | DevTools: fast restart in dev, proven absent from the jar: [note 21](21-spring-boot-devtools.md) |
| 🔜 | Request/correlation ID filter (MDC) |

## Phase 3: Building Job Finder

| Status | Topic | In Job Finder |
|--------|-------|---------------|
| ✅ | Architecture: modular monolith + clean layers, enforced by Spring Modulith + ArchUnit ([note 19](19-clean-architecture.md), [ADR 0001](../docs/adr/0001-modular-clean-architecture.md)) | Every feature module |
| 🧪 | REST APIs with Spring Web MVC: `@RestController`, DTOs, status codes ([note 16](16-rest-controller.md)), request binding ([note 17](17-request-mapping-and-binding.md)); validation 🔜 | `GET /jobs`, `POST /jobs` |
| ✅ | API versioning: path `/api/v1` with Spring 7 built-in support, tested ([note 18](18-api-versioning.md)) | Safe evolution of the API |
| ✅ | Problem Details (RFC 9457) error format enabled + tested ([note 16 §7](16-rest-controller.md)) | Standard error responses |
| 🔜 | Exception handling: `@RestControllerAdvice` mapping domain exceptions to Problem Details | Clean error responses |
| 🧪 | JDBC fundamentals, connection pooling, `JdbcClient` ([note 22](22-working-with-a-database-in-java.md)) | Foundation for persistence |
| 🧪 | Spring Data vs Spring Data JPA, repositories, derived queries, dirty checking ([note 23](23-spring-data-vs-spring-data-jpa.md)); Spring Data JPA ↔ Hibernate internals ([note 24](24-spring-data-jpa-and-hibernate.md)); components: projections, N+1, Specifications, auditing, `@Version` ([note 25](25-spring-data-jpa-components.md)); interfaces, keyset scrolling ([note 26](26-spring-data-interfaces.md)); derived query methods ([note 27](27-derived-query-methods.md)); `@Query` JPQL/native, `@Modifying` ([note 28](28-query-annotation.md)); Lombok and entity boilerplate ([note 29](29-lombok.md)) | Choosing the persistence approach |
| 🔜 | Database with Spring Data JPA + PostgreSQL | `Job`, `Company`, `Application` entities |
| 🔜 | Schema migrations with Flyway | Versioned database changes |
| 🔜 | Search, filtering, pagination | Search jobs by title, location, salary |
| 🔜 | Spring Security + JWT | Job seekers and employers log in |
| 🔜 | Integration testing: `@SpringBootTest`, Testcontainers | Tests against a real DB |
| 🔜 | API docs with OpenAPI / Swagger UI | Portfolio-friendly API explorer |
| 🔜 | Docker + docker-compose | One-command local run |
| 🔜 | CI with GitHub Actions | Build and test on every push |

> The roadmap will change as the project evolves. That's fine; update this file whenever
> it does.
