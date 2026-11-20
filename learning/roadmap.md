# Roadmap: From Spring Core to the Job Finder App

This is the planned learning path. Each item becomes its own numbered note once it is
learned or applied, and its status changes in the [README](README.md).

Legend: ✅ applied · 🧪 learned, not applied · 🔜 planned

## Phase 1: Spring Core (plain Spring Framework)

| Status | Topic | Key ideas |
|--------|-------|-----------|
| ✅ | IoC container, `@Configuration`, `@Bean`, `getBean` | [Note 02](02-spring-ioc-and-beans.md) |
| ✅ | Multiple beans of the same type, `@Bean("name")`, aliases, `@Description` | [Note 03](03-multiple-beans-and-naming.md) |
| 🧪 | `@Primary`, `@Qualifier`, `@Fallback`, `List`/`Map` injection | [Note 03 §8](03-multiple-beans-and-naming.md); will be ✅ once injection is in the code |
| ✅ | `@Component`, `@ComponentScan` | [Note 04](04-component-scan-and-bean-lifecycle.md) |
| 🧪 | Stereotypes in depth: `@Service`, `@Repository`, `@Controller`, `@RestController`, custom stereotypes | [Note 07](07-stereotype-annotations.md) |
| 🔜 | Dependency injection: constructor (preferred), setter, field | `@Autowired`, why constructor injection plus `final` fields is best practice |
| 🔜 | Bean scopes: singleton, prototype | Lifetime of objects |
| ✅ | Bean lifecycle: `InitializingBean`/`DisposableBean` (applied), `@PostConstruct`/`@PreDestroy` (🧪 learned) | [Note 04](04-component-scan-and-bean-lifecycle.md) |
| 🧪 | Modular configuration with `@Import` | [Note 06](06-import-annotation.md) |
| 🔜 | Eager vs lazy initialization: `@Lazy` | When beans are created |
| 🔜 | Externalised configuration: `@PropertySource`, `@Value` | Keeping settings out of code |
| 🔜 | AOP basics: `@Aspect`, `@Before`, `@Around` | Cross-cutting concerns such as logging and timing |
| 🔜 | Unit testing with JUnit 5 | `mvn test`, assertions |

## Phase 2: Spring Boot

| Status | Topic |
|--------|-------|
| 🔜 | Migrating this project to Spring Boot 4 (starters, `@SpringBootApplication`, auto-configuration) |
| 🔜 | `application.yml`, profiles (`dev`, `prod`) |
| 🔜 | Logging (SLF4J + Logback) |

## Phase 3: Building Job Finder

The app follows the layered structure from [note 05](05-backend-layers.md) (🧪 learned).

| Status | Topic | In Job Finder |
|--------|-------|---------------|
| 🔜 | REST APIs with Spring Web MVC: `@RestController`, DTOs, validation | `GET /jobs`, `POST /jobs` |
| 🔜 | Exception handling: `@RestControllerAdvice`, problem details | Clean error responses |
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
