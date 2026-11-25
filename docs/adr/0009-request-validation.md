# ADR 0009: Request validation

- **Status:** Accepted; extended by [ADR 0010](0010-pagination-and-sorting.md), [ADR 0011](0011-filtering-and-search.md) and [ADR 0016](0016-openapi-documentation.md) (no Jackson 2 converter reads bodies)
- **Date:** 2026-11-24
- **Implements:** [ADR 0005](0005-dtos-at-the-api-boundary.md) decision 8. **Extends:**
  [ADR 0006](0006-global-exception-handling.md), [ADR 0008](0008-exception-handling-hardening.md)

## Context

ADR 0005 planned Bean Validation on request DTOs; ADR 0006 defined how violations are reported. Before the
first real endpoint, the behaviour of Spring Boot 4.2 / Hibernate Validator 9.1 / Jackson 3.1 was measured
over real HTTP (`learning/34-validation.md`). Mistakes that **compile, start and accept invalid input**:

1. A `@RequestBody` without `@Valid`: no constraint runs (`200` for an empty title).
2. A nested object or a list of objects without `@Valid`: its constraints are skipped.
3. Jackson converts scalars silently: `2.7` → `2` for an `int` (data loss), `"5000"` → `5000`,
   `""` → `null`, `123` / `true` → `"123"` / `"true"`.

Inconsistencies in the error contract:

4. Query parameter failures (`?page_size=abc`, an unknown enum value, a missing required parameter or
   header) used Spring's generic `400` format, not `validation-error`, and **echoed the rejected value**
   (`"Failed to convert 'page_size' with value: 'abc'"`).
5. An exception thrown by a request type's constructor was reported as `malformed-request-body`
   ("not valid JSON"), although the JSON was valid.
6. A missing primitive (`int`) failed inside Jackson, before Bean Validation: one error at a time, with the
   message "has an invalid value or type" for a field that was simply absent.
7. Violations carried only English text. ADR 0008 fixed the language; clients had nothing stable to
   branch on or translate.

## Decision

1. **Every violation has a machine-readable `code`**: the Bean Validation constraint name (`NotBlank`,
   `Size`, a custom `ConsistentRange`), or a binding code: `UnknownProperty`, `InvalidType`,
   `InvalidValue`, `Required`. Codes are part of the API contract (catalogue in `docs/problems.md`);
   `detail` stays English text for developers.
2. **One format for all input errors**: invalid or missing query/path parameters and missing required
   headers are `validation-error` problems with `parameter` entries. Rejected values are never echoed.
3. **Strict JSON types**: `spring.jackson.mapper.allow-coercion-of-scalars=false`,
   `spring.jackson.deserialization.accept-float-as-int=false`, and a `JsonMapperBuilderCustomizer`
   (`JsonBindingConfig`) that rejects numbers and booleans for string properties. A wrong type is
   `InvalidType` at the field's pointer.
4. **Request DTO conventions**, enforced by the ArchUnit rule `REQUEST_BODIES_ARE_VALIDATED`:
   `@Valid` on every `@RequestBody`; `@Valid` on every property whose type is (or contains) a request type
   of our own, put on the property itself (`@Valid List<Item> items`); **no primitives**: wrapper types
   with `@NotNull`, so a missing value is reported together with the other errors.
5. **Validate with constraints, not constructors.** Cross-field rules are class-level constraints that
   report on the field to fix (`addPropertyNode`). If a request type's constructor throws anyway, the
   handler reports `InvalidValue` at the object's pointer without the exception message.
6. **Validation belongs at the API boundary.** Use-case and domain code may check invariants
   (`@Validated` services, constructor checks), but a violation there is a bug in our code: `500`, logged.
7. **Separate request DTOs per operation** instead of validation groups (ADR 0005: one DTO per use case).
8. Every string and collection in a request DTO gets an upper bound (`@Size(max = ...)`), so input size is
   limited by contract (and the error message isn't "between 2 and 2147483647").
9. Locked in by `ValidationTests` (15 tests) and `ArchitectureRulesTests`. Mutation checks: removing the
   parameter handling, the coercion properties, the coercion customizer or the constructor case, or a
   `@Valid` in the compliant fixture, each fails the test that guards it.

## Consequences

**Positive**
- Invalid input is rejected before it reaches use cases, and the common ways to forget validation are
  build failures.
- One error format with stable codes for bodies, parameters and headers; frontends can map codes to their
  own messages.
- No silent data changes from type coercion.

**Negative / accepted trade-offs**
- Strict types reject clients that send numbers as strings. That is intended: the documented JSON types
  are the contract.
- Wrapper types in DTOs need `@NotNull` for required numbers (more annotations than `int`).
- The ArchUnit rule can't see type-use annotations, so `List<@Valid Item>` must be written
  `@Valid List<Item>` (equivalent for Bean Validation).
- `@Email` accepts addresses like `a@b` (only syntax is checked). Real verification needs a confirmation
  mail; add `@Pattern` only if a stricter syntax is required.

**Follow-ups**
- A request size limit (bytes) at the server, independent of DTO constraints.

## References

- Jakarta Bean Validation 3.1 specification; Hibernate Validator 9.1 reference
- Spring Framework reference: "Validation", "Method Validation"
- Learning note: `learning/34-validation.md`
