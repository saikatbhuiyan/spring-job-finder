# 05 — Layers in a Backend Application

## Goal

Understand how a backend is **organised into layers**, what each layer is responsible
for, and how this maps to Spring's stereotype annotations (`@Controller`, `@Service`,
`@Repository`). This is the structure Job Finder will follow.

Status: 🧪 **learned and verified in a demo**, not yet in the project code. It becomes ✅
when we build the first Job Finder feature.

---

## 1. Why split an application into layers?

Imagine all of Job Finder in one class: it parses HTTP requests, checks business rules,
writes SQL, and formats JSON. It works, until:

- you switch from an in-memory list to PostgreSQL, and have to edit code that also handles HTTP;
- you want to unit-test the "a job's salary can't be negative" rule, but it's tangled up with
  database code;
- two developers edit the same giant file.

**Layering = separation of concerns.** Each layer has **one job**, and only talks to the
layer directly below it.

| Benefit | What it means |
|---------|---------------|
| Easier to change | Swap the database and only the repository layer changes |
| Easier to test | Test business rules in the service with a fake repository |
| Easier to understand | You know where to look: an HTTP bug is in the controller, a rule bug is in the service |
| Teamwork | People can work on different layers without conflicts |

---

## 2. The layers

```text
          Client (browser, mobile app, Postman)
                     │  HTTP request: GET /jobs?keyword=java
                     ▼
┌──────────────────────────────────────────────────────┐
│ 1. Presentation / Web layer        @RestController   │  HTTP in/out, input validation,
│    JobController                                     │  DTO ⇄ JSON
└──────────────────────────┬───────────────────────────┘
                           │ calls
                           ▼
┌──────────────────────────────────────────────────────┐
│ 2. Service / Business layer        @Service          │  Business rules, workflows,
│    JobService                                        │  transactions
└──────────────────────────┬───────────────────────────┘
                           │ calls
                           ▼
┌──────────────────────────────────────────────────────┐
│ 3. Data access / Persistence layer @Repository       │  Read/write the database
│    JobRepository                                     │
└──────────────────────────┬───────────────────────────┘
                           ▼
                    Database (PostgreSQL)

  Shared by all layers:  Domain model (Job, Company entities)
  Cross-cutting:         config, security, exception handling, logging
```

### Layer 1: Presentation (Web / API), with `@Controller` / `@RestController`

**Responsible for:** the "outside world" contract.
- Mapping URLs and HTTP methods to Java methods (`GET /jobs` → `getJobs()`).
- Reading request data (path, query parameters, JSON body) and **validating its shape**
  (is the field present? is it a number?).
- Converting results into the response (**DTOs** → JSON) and choosing HTTP status codes
  (200, 201, 404).

**Must NOT:** contain business rules or talk to the database directly.

### Layer 2: Service (Business logic), with `@Service`

**Responsible for:** what the application actually *does*.
- Business rules: "an employer can only edit their own job posts", "a job seeker can't
  apply twice to the same job".
- Coordinating several repositories or external services in one operation.
- Transactions: "save the application **and** update the counter, or do neither".

**Must NOT:** know anything about HTTP (no request objects, no status codes). The same
service should work whether it's called from a REST API, a scheduled job, or a test.

### Layer 3: Data access (Persistence), with `@Repository`

**Responsible for:** storing and loading data. SQL, JPA, or calls to a data store.
- `findById`, `findByTitleContaining`, `save`, `delete`.
- Bonus from `@Repository`: Spring can translate database-specific exceptions into its
  own `DataAccessException` hierarchy (🔜 when we add JPA).

**Must NOT:** contain business rules ("only jobs with salary > X") unless it's purely a
query filter.

### Supporting pieces (not layers, but used by them)

| Piece | What it is | Example |
|-------|------------|---------|
| **Entity / Domain model** | Java object that matches a database table | `Job(id, title, company, salary)` |
| **DTO** (Data Transfer Object) | Object shaped for the API: what the client sends or receives | `JobResponse(title, company)` |
| **Configuration** | `@Configuration` classes, properties | `ProjectConfig` |
| **Cross-cutting** | Concerns every layer has | Security, logging, global exception handling |

#### Why DTOs instead of returning entities directly?

- **Don't leak data:** the entity may have fields the client must not see (internal
  notes, password hashes, or in the demo below, `salary`).
- **Stability:** you can change the database table without breaking API clients.
- **Validation:** the request DTO describes exactly what a client may send.

---

## 3. The golden rules

1. **Dependencies point downward only.** Controller → Service → Repository. A repository
   never calls a service; a service never calls a controller.
2. **No skipping layers.** A controller never calls a repository directly, even for a
   "simple" read. Business rules tend to appear later, and then there's no place for them.
3. **Each layer speaks its own language.** Controllers deal in HTTP and DTOs, services in
   domain objects and rules, repositories in persistence.
4. **Inject dependencies through constructors** (DI, note 02). Each layer receives the
   layer below; it never creates it with `new`.

---

## 4. Verified demo: the layers wired by Spring

I built a tiny three-layer Job Finder in a scratch folder (plain Spring, no web server yet)
to check how the pieces connect.

```java
@Repository
public class JobRepository {
    private final List<Job> jobs = List.of(
        new Job(1, "Java Developer", "Acme", 90000),
        new Job(2, "Python Developer", "Globex", 85000),
        new Job(3, "Senior Java Engineer", "Initech", 120000));

    public List<Job> findAll() { return jobs; }
}

@Service
public class JobService {
    private final JobRepository repo;
    public JobService(JobRepository repo) { this.repo = repo; }   // injected by Spring

    public List<Job> search(String keyword) {
        if (keyword == null || keyword.isBlank())
            throw new IllegalArgumentException("keyword is required");   // business rule
        return repo.findAll().stream()
                   .filter(j -> j.title().toLowerCase().contains(keyword.toLowerCase()))
                   .toList();
    }
}

@Controller
public class JobController {
    private final JobService service;
    public JobController(JobService service) { this.service = service; }

    public List<JobResponse> getJobs(String keyword) {             // later: @GetMapping("/jobs")
        return service.search(keyword).stream()
                      .map(j -> new JobResponse(j.title(), j.company()))   // entity → DTO
                      .toList();
    }
}
```

Output (with log lines in each layer):

```text
[Controller] GET /jobs?keyword=java
  [Service] search("java") -> applying business rules
    [Repository] findAll() -> reading the data store
Response: [JobResponse[title=Java Developer, company=Acme],
           JobResponse[title=Senior Java Engineer, company=Initech]]

[Controller] GET /jobs?keyword=
  [Service] search("") -> applying business rules
Error from service layer: keyword is required
```

What this verified:

1. **The call flows down the layers and back up**, exactly as in the diagram.
2. **Spring wired everything automatically.** Each class has a **single constructor**, so
   Spring injects its dependency **without `@Autowired`** (the rule since Spring 4.3).
3. **The DTO hid a field.** `salary` is in the `Job` entity but not in `JobResponse`.
4. **The business rule lives in the service.** The repository was never called for a blank
   keyword.
5. `@Controller`, `@Service` and `@Repository` all come from `spring-context`
   (package `org.springframework.stereotype`), which we already have. `@RestController`
   and `@GetMapping` need Spring Web (🔜).

> `record Job(long id, String title, ...)` is a Java **record**: a compact, immutable data
> class. The compiler generates the constructor, the accessors (`title()`), `equals`,
> `hashCode` and `toString`. Records are perfect for DTOs.

---

## 5. How to organise the packages

### Option A: package by layer

```text
com.shahabuddin.jobfinder
├── controller/   JobController, CompanyController, ApplicationController
├── service/      JobService, CompanyService, ApplicationService
├── repository/   JobRepository, CompanyRepository, ...
├── model/        Job, Company, Application
└── dto/          JobResponse, CreateJobRequest, ...
```

### Option B: package by feature ✅ recommended for Job Finder

```text
com.shahabuddin.jobfinder
├── job/          JobController, JobService, JobRepository, Job, JobResponse
├── company/      CompanyController, CompanyService, CompanyRepository, Company
├── application/  ApplicationController, ApplicationService, ...
└── common/       config, exception handling, security
```

**Why by feature:** everything about "jobs" sits in one folder, so a change to jobs touches
one package. Classes can be package-private (no `public`), which hides internals from other
features. As the app grows, a feature can even be split into its own service. Layering still
exists **inside** each feature package. Package-by-layer is fine for tiny apps and is what
many tutorials show.

---

## 6. Plan for Job Finder 🔜

| Layer | Job Finder classes |
|-------|--------------------|
| Web | `JobController` (`GET /jobs`, `POST /jobs`), `CompanyController`, `ApplicationController` |
| DTO | `JobResponse`, `CreateJobRequest`, `ApplyRequest` |
| Service | `JobService` (search, post, close), `ApplicationService` (apply once, track status) |
| Repository | `JobRepository`, `CompanyRepository`, `ApplicationRepository` (Spring Data JPA) |
| Entity | `Job`, `Company`, `Application`, `User` |
| Cross-cutting | `GlobalExceptionHandler` (`@RestControllerAdvice`), security config |

---

## Common mistakes

| Mistake | Why it hurts |
|---------|--------------|
| Business logic in the controller | Can't reuse it from other entry points; hard to test |
| Controller calls the repository directly | Skips business rules; nowhere to add them later |
| Service uses HTTP classes (`HttpServletRequest`, status codes) | Service becomes tied to the web; can't call it from a scheduler or test |
| Returning entities as JSON | Leaks fields, couples the API to the table structure |
| A repository calling a service | Upward dependency; risk of circular dependencies |
| One "god" service for everything | Same problems as no layers at all |

## Interview questions

**Q: What are the typical layers of a Spring backend?**
Presentation (`@RestController`), service (`@Service`), and data access (`@Repository`), plus the
domain model and DTOs. Cross-cutting concerns like security and exception handling sit beside them.

**Q: Why shouldn't a controller call a repository directly?**
Business rules belong in the service. Skipping it scatters logic across controllers and
makes it impossible to enforce rules consistently.

**Q: What is a DTO and why use it?**
An object shaped for data transfer over the API. It hides internal fields, decouples the API
from the database schema, and defines what clients may send.

**Q: Package by layer or by feature?**
By feature scales better: related code stays together and can be package-private. Layering
still exists inside each feature.

**Q: Does `@Service` do anything special?**
No. Technically it's a `@Component`; it documents the class's role. `@Repository` adds exception
translation, and `@Controller` registers request handlers (with Spring Web).

**Q: Do you need `@Autowired` on a constructor?**
Not if the class has only one constructor (Spring 4.3+). Verified in the demo.
