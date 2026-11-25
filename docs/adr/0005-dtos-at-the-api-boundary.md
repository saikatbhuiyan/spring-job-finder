# ADR 0005: DTOs at the API boundary; endpoints never expose domain types

- **Status:** Accepted; decision 7 implemented by [ADR 0010](0010-pagination-and-sorting.md), decision 8 by [ADR 0009](0009-request-validation.md); extended by [ADR 0016](0016-openapi-documentation.md) (bodies are JSON only)
- **Date:** 2026-11-22

## Context

ADR 0001 places request/response DTOs in each module's `api` layer, but nothing enforced it, and the
layer rule deliberately lets `api` depend on `domain` (so controllers can map entities to DTOs). A
controller could therefore accept or return a JPA entity directly.

A scratch app (Boot 4.2, Jackson 3.1, Hibernate 7.4, `open-in-view=false`) showed what that costs
(details in `learning/30-dto-pattern.md`):

- **Lazy relations:** returning an entity failed with `500` (`Could not initialize proxy … no session`).
- **Bidirectional relations:** `Job → Company → jobs → Job …` hit Jackson's nesting limit (500) *after*
  the `200` status was sent, so the client received a **truncated 14 KB body with status 200**.
- **Data leaks:** internal fields (`internalNotes`, `version`) are serialized unless someone remembers to
  hide each one.
- **Overposting (mass assignment):** `@RequestBody Job` let a client set `featured=true`,
  `status=CLOSED` and `internalNotes`, and all of them were stored.
- **Coupling:** renaming an entity field silently changes the public API.

## Decision

1. **Every endpoint accepts and returns DTOs, never domain types.** Request and response DTOs are
   **records** in the module's `api` package (`CreateJobRequest`, `JobResponse`, `JobSummaryResponse`).
   **Enforced** by ArchUnit rule `ENDPOINTS_DO_NOT_EXPOSE_DOMAIN_TYPES`. It checks the parameters and return
   type of every request-mapped method, including generic arguments (`ResponseEntity<List<Job>>`), and is
   tested against a violating fixture (`LeakyController`).
2. **Request DTOs are separate from response DTOs.** A request contains only the fields a client may set,
   which prevents overposting by construction. Ids, status, audit fields and versions are never request fields
   unless a use case explicitly allows them.
3. **The application layer never sees API DTOs.** Controllers pass parameters or command records to use cases
   (already enforced: no layer may depend on `api`).
4. **Mapping is hand-written** (`JobResponse.from(job)` / a private mapper in the controller) while mappings
   are small. Consider MapStruct (with `unmappedTargetPolicy = ERROR`) only when mapping code becomes a real
   maintenance burden. It adds an annotation processor and build configuration (same JDK 23+ processor setup
   as Lombok, note 29).
5. **Data for a response is loaded inside the use case.** With `open-in-view=false`, lazy relations can't
   be read in the controller (verified: `LazyInitializationException`). Use cases fetch what the response
   needs (`join fetch` / `@EntityGraph`) or return read-model projections.
6. **Unknown request properties are rejected:**
   `spring.jackson.deserialization.fail-on-unknown-properties=true` (Boot's default is to ignore them).
   A misspelled or unsupported field gets a `400` Problem Details response instead of being silently
   dropped. Locked in by `RequestBodyBindingTests`.
7. **Paged responses never serialize `PageImpl`.** Spring Data warns that its JSON shape isn't stable.
   When Spring Data is added, set `spring.data.web.pageable.serialization-mode=via-dto` (verified shape:
   `{"content":[…],"page":{"size","number","totalElements","totalPages"}}`).
8. **Request validation** (Bean Validation `@Valid` on request DTOs) is added together with the first
   endpoint that accepts a body.

## Consequences

**Positive**
- The API contract is explicit and independent of the persistence model. Entities can change freely.
- Overposting and accidental data leaks are prevented structurally, not by remembering `@JsonIgnore`.
- No lazy-loading or recursion failures in serialization.

**Negative / accepted trade-offs**
- More classes and mapping code per endpoint.
- Strict unknown-property handling means clients must not send extra fields. That is intended for this API,
  and a field can be added to a request DTO when it becomes supported.

**Follow-up found while verifying this ADR**
- Validation and unknown-property errors currently return a generic `detail` ("Invalid request content.",
  "Failed to read request") without saying *which* field failed. Unhandled exceptions return Boot's legacy
  `application/json` body instead of Problem Details, contrary to ADR 0003 (no internal message is leaked).
  Both will be addressed by the global `@RestControllerAdvice` planned in ADR 0003.

## Alternatives considered

- **Expose entities and hide fields with `@JsonIgnore` / `@JsonView`:** rejected. It's opt-out (every new
  field leaks by default), still couples API to schema, and doesn't solve lazy loading or overposting.
- **MapStruct by default:** deferred (decision 4).
- **One DTO for request and response:** rejected. Server-controlled fields would become client-settable.

## References

- Learning note: `learning/30-dto-pattern.md`
- ADR 0001 (layers), ADR 0003 (Problem Details)
- Martin Fowler, *Patterns of Enterprise Application Architecture*: "Data Transfer Object"
