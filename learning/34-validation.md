# 34 — Validation in Spring Boot REST APIs

## Goal

A REST API must reject bad input **before** it reaches business code, and tell the client exactly what
to fix. This note explains, step by step, how validation works in Spring Boot: Bean Validation
constraints, nested objects, query parameters, custom and cross-field rules, and what Jackson does to
your data *before* validation even starts. Every behaviour in this note was measured against the running
app; several defaults turned out to accept invalid input silently.

Status: ✅ **applied** ([ADR 0009](../docs/adr/0009-request-validation.md); the error format is
[note 31](31-global-exception-handling.md)). Boot 4.2.0, Hibernate Validator 9.1, Jackson 3.1.

---

## 1. Three checks, in order

```text
HTTP request
   │
   ▼
① Binding (Jackson, Spring conversion)   Is it valid JSON? Does each value have the right TYPE?
   │                                       "salary": "abc" for a number → fails here
   ▼
② Validation (Bean Validation)            Are the values ACCEPTABLE?
   │                                       "title": "" with @NotBlank → fails here
   ▼
③ Business rules (use case / domain)      Is it allowed NOW?
                                           "job is already closed" → ConflictException (409)
```

- ① and ② are about the **shape** of the input and need no database. They answer `400 validation-error`.
- ③ needs the current state ("does this email already exist?"). It belongs in the use case and answers
  with a domain exception (404, 409).

Mixing them up causes most validation bugs. A uniqueness check in a validator, for example, races with
concurrent requests; the database constraint plus a `409` is the real protection.

## 2. Setup

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

That brings **Jakarta Bean Validation** (the standard: `jakarta.validation.constraints.*` annotations)
and **Hibernate Validator** (the implementation). Spring Boot then auto-configures:

- a `Validator` bean that Spring MVC uses for `@Valid` arguments;
- method validation for beans annotated `@Validated` (§11).

Without the starter, `@NotBlank` still compiles (if the API jar is present) but **nothing checks it**.

## 3. The first validated endpoint

```java
public record CreateJobRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Positive Integer openings) {
}

@PostMapping
JobResponse create(@Valid @RequestBody CreateJobRequest request) { ... }
```

- The **constraints** go on the record components.
- **`@Valid`** on the parameter tells Spring to validate the object after Jackson created it.
- If anything fails, the controller method **doesn't run**. Spring throws
  `MethodArgumentNotValidException`, and `GlobalExceptionHandler` turns it into:

```json
{
  "type": ".../problems.md#validation-error",
  "title": "Invalid request",
  "status": 400,
  "errors": [
    { "pointer": "#/openings", "code": "NotNull", "detail": "must not be null" },
    { "pointer": "#/title", "code": "NotBlank", "detail": "must not be blank" }
  ]
}
```

**All** violations are reported at once, so the client can fix everything in one go.

### Trap: forgetting `@Valid`

Measured: the same endpoint **without `@Valid`** answered `200 OK` for `{"title": ""}`. The constraints
are just annotations; nothing reads them unless `@Valid` (or `@Validated`) asks for validation.

## 4. The built-in constraints

| Constraint | Checks | `null` |
|---|---|---|
| `@NotNull` | not null | ❌ fails |
| `@NotEmpty` | not null, length/size > 0 (strings, collections, maps, arrays) | ❌ fails |
| `@NotBlank` | not null, at least one non-whitespace character (strings) | ❌ fails |
| `@Size(min, max)` | length of a string, size of a collection | ✅ passes |
| `@Min`, `@Max`, `@Positive`, `@PositiveOrZero`, `@Negative`, `@DecimalMin`, `@DecimalMax` | numeric bounds | ✅ passes |
| `@Digits(integer, fraction)` | number of digits (money!) | ✅ passes |
| `@Pattern(regexp)` | regular expression | ✅ passes |
| `@Email` | e-mail syntax | ✅ passes |
| `@Past`, `@Future`, `@PastOrPresent`, `@FutureOrPresent` | dates and times | ✅ passes |
| `@AssertTrue`, `@AssertFalse` | booleans | ✅ passes |

**Most constraints accept `null`.** That's deliberate: "is it present?" (`@NotNull`) and "is it valid if
present?" (`@Size`) are separate questions. Combine them: `@NotNull @Size(max = 200)`.

Measured with the three ways a string can be "empty":

| Value | `@NotNull` | `@NotEmpty` | `@NotBlank` | `@Size(min = 2)` |
|---|---|---|---|---|
| `null` | ❌ | ❌ | ❌ | ✅ |
| `""` | ✅ | ❌ | ❌ | ❌ |
| `"   "` | ✅ | ✅ | ❌ | ✅ (3 characters!) |

For text a human types, `@NotBlank` is almost always the right choice.

**Always give `@Size` a `max`.** `@Size(min = 2)` reported *"size must be between 2 and 2147483647"*.
More importantly, an upper bound on every string and list is part of the contract: it stops a client from
sending a 10 MB title.

**`@Email` is weak.** Measured: `a@b` and `a@b.c` pass; `a`, `a b@c.com` and `a@-b.com` fail. It checks
syntax loosely; whether an address exists can only be proven by sending a confirmation mail.

## 5. Nested objects and lists

```java
public record CreateJobRequest(
        @NotBlank String title,
        @NotNull @Valid CompanyRef company,          // @Valid: validate INSIDE company too
        @Size(max = 20) @Valid List<Tag> tags,       // @Valid on a list: validate every element
        @Size(max = 20) List<@NotBlank String> labels) { // constraint on each element
}
public record CompanyRef(@NotBlank String name) {}
```

Measured: without `@Valid`, `{"company": {"name": ""}}` and `{"tags": [{"name": ""}]}` were **accepted**.
Bean Validation only "cascades" into an object when told to. With `@Valid`, the pointers show exactly
where the problem is: `#/company/name`, `#/tags/0/name`.

`List<@NotBlank String>` puts the constraint on each **element** (a "container element constraint"):
`#/labels/1` names the second label.

## 6. What Jackson does *before* validation

Validation sees Java objects, not JSON. Jackson builds those objects first, and its defaults are
**lenient**. Measured with Jackson 3 defaults:

| JSON sent | Java field | Jackson's default | Problem |
|---|---|---|---|
| `"openings": 2.7` | `int` | becomes `2` | **silent data loss** |
| `"salary": "5000"` | `int` | becomes `5000` | hides a client bug |
| `"boxed": ""` | `Integer` | becomes `null` | an empty string isn't "no value" |
| `"title": 123` | `String` | becomes `"123"` | wrong type accepted |
| `"title": true` | `String` | becomes `"true"` | wrong type accepted |

Job Finder makes types strict (the same idea as rejecting unknown properties, ADR 0005):

```properties
spring.jackson.mapper.allow-coercion-of-scalars=false      # "5000" → number, "" → null
spring.jackson.deserialization.accept-float-as-int=false  # 2.7 → 2
```

Boot has no property for coercion **into** strings, so a customizer does that part:

```java
@Bean
JsonMapperBuilderCustomizer rejectScalarsForStringProperties() {
    return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
}
```

Mutation check: removing the properties broke exactly the three number cases; removing the customizer
broke exactly the two string cases. Each part covers its own half.

### Primitives vs wrapper types

With an `int` field, a missing value can't be `null`. Jackson 3 rejects it (it doesn't silently use `0`
anymore), but **inside Jackson**, before Bean Validation runs. Measured:

```json
// {"title": "", "openings": null} with int openings:
"errors": [{ "pointer": "#/openings", "detail": "has an invalid value or type" }]
```

Only **one** error (Jackson stops at the first problem; the blank title isn't reported) with a misleading
message for a value that's simply missing. With `@NotNull Integer openings`, Bean Validation reports
`#/openings NotNull` **and** `#/title NotBlank` together. Rule: **no primitives in request DTOs**.
(Responses can use primitives freely.)

## 7. Query parameters, path variables and headers

Since Spring Framework 6.1, constraints can go **directly on controller parameters**, with no
`@Validated` on the class:

```java
@GetMapping
List<JobSummary> search(@RequestParam(name = "q") String query,
                        @RequestParam(name = "page_size", defaultValue = "20") @Max(100) Integer pageSize,
                        @RequestParam(required = false) Status status,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) { ... }
```

A violated constraint (`page_size=500`) throws `HandlerMethodValidationException`, which became a
`validation-error` with `"parameter": "page_size"` in note 31. But the *other* parameter failures didn't.
Measured before the fix:

| Request | Response before |
|---|---|
| `?page_size=abc` | `{"title":"Bad Request","detail":"Failed to convert 'page_size' with value: 'abc'"}` |
| `?status=OPENX` | same format, echoing `'OPENX'` |
| no `q` | `{"title":"Bad Request","detail":"Required parameter 'q' is not present."}` |

A different format than body errors, no `type`, and the rejected value **echoed back**. Now all of them
are `validation-error` entries:

| Request | `parameter` | `code` |
|---|---|---|
| `?page_size=abc` | `page_size` | `InvalidType` |
| `?status=OPENX` | `status` | `InvalidType` |
| `?page_size=500` | `page_size` | `Max` |
| no `q` | `q` | `Required` |
| no `Idempotency-Key` header | `Idempotency-Key` | `Required` |

Done by overriding three more methods of `ResponseEntityExceptionHandler`: `handleTypeMismatch`,
`handleMissingServletRequestParameter` and `handleServletRequestBindingException` (for
`MissingRequestHeaderException`).

## 8. Error codes: something stable for clients

`detail` is English text (note 33 fixed the language). A frontend that wants to show "Bitte einen Titel
eingeben" needs something **stable** to translate. Each violation now has a `code`:

- for constraints, the constraint name: `NotBlank`, `Size`, `Max`, a custom `ConsistentRange`;
- for binding problems: `UnknownProperty`, `InvalidType`, `InvalidValue`, `Required`.

Where does `NotBlank` come from? Spring gives every violation a list of **message codes**, from most to
least specific:

```text
NotBlank.createJobRequest.title
NotBlank.title
NotBlank.java.lang.String
NotBlank                       ← the last one is the bare constraint name
```

```java
private static String code(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    return codes != null && codes.length > 0 ? codes[codes.length - 1] : Violation.INVALID_VALUE;
}
```

The codes are documented in [`docs/problems.md`](../docs/problems.md) and are part of the API contract.

## 9. Custom constraints and cross-field rules

"The maximum salary must not be less than the minimum" involves **two** fields. A field annotation can't
see the other field, so the constraint goes on the **class**:

```java
@ConsistentRange
public record SalaryRange(@NotNull @PositiveOrZero Integer min, @NotNull Integer max) {}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentRangeValidator.class)
public @interface ConsistentRange {
    String message() default "must not be less than min";
    Class<?>[] groups() default {};                    // required by the spec
    Class<? extends Payload>[] payload() default {};   // required by the spec
}

class ConsistentRangeValidator implements ConstraintValidator<ConsistentRange, SalaryRange> {
    @Override
    public boolean isValid(SalaryRange range, ConstraintValidatorContext context) {
        if (range == null || range.min() == null || range.max() == null || range.min() <= range.max()) {
            return true;                                // nulls are @NotNull's job
        }
        context.disableDefaultConstraintViolation();    // don't report on the whole object...
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("max")                 // ...report on the field to fix
                .addConstraintViolation();
        return false;
    }
}
```

Step by step:

1. `@Constraint(validatedBy = ...)` links the annotation to its validator.
2. `message`, `groups`, `payload` must exist on every constraint annotation (the spec requires them).
3. `isValid` returns `true` for `null`. Each constraint checks one thing; presence is `@NotNull`'s job.
4. Without `addPropertyNode`, the error would point at the whole object (`#/salary`). With it:
   `{"pointer": "#/salary/max", "code": "ConsistentRange"}` (measured).

### Anti-pattern: validating in the constructor

```java
record SelfCheckingRange(int from, int to) {
    SelfCheckingRange { if (from > to) throw new IllegalArgumentException("from must not exceed to"); }
}
```

Tempting (records have compact constructors), but Jackson calls that constructor while **reading the
JSON**. Measured before the fix: the client got `malformed-request-body` *"The request body is missing or is
not valid JSON."*, which is false, and only one problem at a time. The handler now reports it as
`{"pointer": "#", "code": "InvalidValue"}` (without the exception message, which isn't written for
clients), but the right fix is a constraint.

## 10. Validation groups (and why we don't use them)

Groups let one DTO have different rules per operation:

```java
interface OnCreate {}
record JobRequest(@NotNull(groups = OnCreate.class) Long id, @NotBlank String title) {}

@PostMapping
void create(@Validated({OnCreate.class, Default.class}) @RequestBody JobRequest request) { ... }
```

Measured: works (`id` and `title` both reported). But each endpoint now silently depends on which groups
it lists, and a constraint without `groups` belongs to `Default` only. Job Finder uses **one request DTO
per operation** instead (ADR 0005): `CreateJobRequest`, `UpdateJobRequest`. Each has exactly the rules of
its operation, visible in one place.

## 11. Validation inside the application (`@Validated` services)

Spring can validate method parameters of any bean annotated `@Validated`:

```java
@Service
@Validated
class PostingService {
    void publish(@NotBlank String title) { ... }
}
```

A violation throws `jakarta.validation.ConstraintViolationException`. Measured: the client gets a **500**
(our catch-all, logged). That is the intended reading in Job Finder: client input was already validated at
the API boundary, so a violation deeper inside means **our code** passed bad data. That's a bug, not the
client's fault. Use service-level constraints as guards, not as the place where client input is checked.

| Where | Checks | Failure means | Status |
|---|---|---|---|
| Request DTO (`@Valid`) | shape of client input | client sent bad input | 400 |
| Use case | rules needing current state (exists? already closed? unique?) | request not allowed now | 404 / 409 |
| Domain object / `@Validated` service | invariants that must never break | bug in our code | 500 |

## 12. Enforcing the conventions automatically

Three of the traps above compile, start and silently accept bad input: a missing `@Valid` on the body, a
missing `@Valid` on a nested property, and primitives. Code review misses these. An ArchUnit rule checks
every `@RequestBody` in every controller and walks its types recursively:

```text
UnvalidatedController.apply(...ApplicationRequest) does not validate its @RequestBody (add @Valid)
...ApplicationRequest.yearsOfExperience is primitive int (use a wrapper type with @NotNull)
...ApplicationRequest.address is not validated (add @Valid to cascade into it)
...ApplicationRequest.previous is not validated (add @Valid to cascade into it)
```

One limitation: ArchUnit can't see annotations on type arguments, so write `@Valid List<Tag> tags`, not
`List<@Valid Tag> tags` (Bean Validation treats them the same).

## 13. Verification summary

| Check | Result |
|-------|--------|
| Probe of current behaviour (blankness, nesting, coercion, primitives, `@Email`, constructor, cross-field, parameters, groups, service, missing `@Valid`) | 11 behaviours measured, 6 problems found |
| `ValidationTests`: 15 tests over real HTTP (codes, cross-field pointer, missing numbers, 5 coercion cases, unknown property, constructor, 4 parameter cases, header, service-level 500) | ✅ |
| `ArchitectureRulesTests`: new rule rejects `UnvalidatedController` (4 messages), accepts the compliant fixture | ✅ |
| Mutation: remove parameter overrides | parameter test fails |
| Mutation: remove coercion properties / customizer | number cases / string cases fail (exactly their half) |
| Mutation: remove the constructor case | constructor test fails |
| Mutation: drop `@Valid` from the compliant fixture's list | compliant-fixture test fails |
| Full suite `mvn verify` | **95 tests, 0 failures** (was 77) |
| Packaged jar, `prod` profile | starts with the strict JSON settings; no WARN/ERROR logs |

---

## Common mistakes

| Mistake | Consequence |
|---------|-------------|
| `@RequestBody` without `@Valid` | No constraint runs (verified: 200 for invalid input) |
| Nested object or list without `@Valid` | Its constraints are skipped (verified) |
| `@Size(max)` without `@NotNull` for a required field | `null` passes |
| `@NotEmpty` for user-typed text | `"   "` passes; use `@NotBlank` |
| `@Size(min = 2)` without `max` | Unbounded input, odd message |
| `int` instead of `@NotNull Integer` in a request | One error at a time, misleading message |
| Jackson's default coercion | `2.7` silently becomes `2` (verified) |
| Validation in a record's constructor | Reported as malformed JSON, one error at a time |
| Uniqueness checks in a `ConstraintValidator` | Race conditions; use a DB constraint + 409 |
| Relying on `@Email` | `a@b` passes |
| Echoing rejected values in errors | Personal data in responses and client logs |
| Validation groups for create vs update | Hidden rules per endpoint; use separate DTOs |
| Expecting `@Validated` service violations to be 400 | They're 500: validate client input at the API |

## Interview questions

**Q: `@Valid` vs `@Validated`?**
`@Valid` (Jakarta) triggers validation and cascades into nested objects. `@Validated` (Spring) also
triggers validation and can select **groups**. On a class, `@Validated` enables method validation for that
bean.

**Q: What's the difference between `@NotNull`, `@NotEmpty` and `@NotBlank`?**
`@NotNull`: not null. `@NotEmpty`: not null and not empty (`""`, empty list). `@NotBlank`: not null and
contains a non-whitespace character (strings only).

**Q: Why does `@Size(max = 10)` accept `null`?**
Most constraints treat `null` as valid, so presence and format are separate rules. Add `@NotNull` for
required fields.

**Q: How do you validate a nested object?**
Annotate the property with `@Valid`. For lists, `@Valid` on the list (or the element type) validates every
element; `List<@NotBlank String>` constrains each element.

**Q: How do you validate a rule involving two fields?**
A class-level custom constraint with a `ConstraintValidator`; report the violation on the field to fix
with `addPropertyNode`.

**Q: How are `@RequestParam` constraints validated in Spring 6.1+?**
Built-in method validation: constraints directly on controller parameters raise
`HandlerMethodValidationException` (400), with no `@Validated` on the controller needed.

**Q: What happens before Bean Validation runs, and why does it matter?**
Jackson converts JSON into objects. By default it coerces types (`"5"` → 5, `2.7` → 2), so invalid input
can be changed before any constraint sees it. Disable coercion for a strict API.

**Q: Where should a "username must be unique" check go?**
In the use case, backed by a unique database constraint, answered with 409. Not in a request validator:
two concurrent requests would both pass the check.
