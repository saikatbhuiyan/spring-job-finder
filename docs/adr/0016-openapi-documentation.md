# ADR 0016: OpenAPI documentation

- **Status:** Accepted
- **Date:** 2026-11-25
- **Extends:** [ADR 0005](0005-dtos-at-the-api-boundary.md) and [ADR 0009](0009-request-validation.md)
  (bodies are JSON only), [ADR 0008](0008-exception-handling-hardening.md) (the global handler is ordered
  first)

## Context

Frontend developers and client generators need a machine-readable description of the API: OpenAPI
(formerly Swagger). It can be written by hand first ("spec-first") or generated from the code
("code-first"). Job Finder's API conventions live in code (versioning, paging, validation, idempotency,
Problem Details), so a hand-written document would drift from them. Measured with springdoc-openapi, the
standard code-first library for Spring MVC (`learning/41-openapi-documentation.md`):

- **Versions**: 3.2.1, the first release built on Spring Boot 4.2 (and on swagger-core 3 / Jackson 3), was
  5 days old. 3.2.0 (Boot 4.1 line, swagger-core 2.2.55) starts on Boot 4.2 and the full suite passes. It
  adds Jackson 2, and its Swagger UI bundles a DOMPurify version with three XSS advisories (fixed in 3.2.1).
- **Out of the box, the document misdescribed the API**: paths as `/api/1/...` (clients call
  `/api/v1/...`); a filter record as one required object parameter `filter`; `PageQuery` as an object
  whose `sort` was `{empty, sorted, unsorted}`; a `POST` answering `201` documented as `200 */*`; no
  `Idempotency-Key`; no error response at all; the internal `/error` controller as seven operations; a
  Problem schema with an invented `properties` member and no `traceId` or `errors`.
- **springdoc registers its own `@RestControllerAdvice`** (a 404 with its own non-Problem body). Ours and
  it were both at the lowest precedence: which was asked first depended on bean registration order.
- **springdoc brought a second way to read request bodies.** Its Jackson 2 YAML module made Spring MVC
  register a YAML converter: a YAML body was bound with none of the JSON rules (`title: 123` became
  `"123"`, unknown properties ignored: `200`), and `Accept: application/yaml` got dates as epoch numbers.
- **On by default**: springdoc serves `/v3/api-docs` and Swagger UI in every environment (and warns
  about it at startup).

## Decision

1. **Code-first with springdoc-openapi 3.2.0** (`springdoc-openapi-starter-webmvc-ui`), OpenAPI 3.1.
   Move to the 3.2.x line built on Boot 4.2 once it is a few weeks old (Jackson 3, fixed Swagger UI).
2. **The document tells the truth about the contract**, corrected in `shared/api/OpenApiConfig` where
   springdoc can't see it:
   - paths as clients call them (`/api/v1/...`); only `/api/**` documented;
   - `PageQuery` as `page` (≥ 0), `size` (1–100, default 20) and a repeatable `sort` whose values are
     the endpoint's `@SortableBy` allowlist, with its default; no `sort` where the endpoint can't be sorted;
   - filter records as the separate query parameters clients send;
   - `Idempotency-Key` on every `POST`/`PATCH` (required with `@Idempotent`), with `409`/`413`/`422` and
     `Idempotent-Replayed` on success;
   - `400`, `429` and `500` on every operation, as `application/problem+json` with a `Problem` schema that
     has the members the API really sends (`traceId`, `errors[]` of `Violation`); `X-Trace-Id` on every
     response; reusable responses (`NotFound`, `Conflict`, ...) for controllers to reference;
   - request bodies (and objects nested in them) with `additionalProperties: false`; response schemas
     stay open, so adding a response property isn't a breaking change.
3. **Controllers declare what only they know**: `@Tag` on the controller, `@Operation(summary)`, the
   success status of a `ResponseEntity` (`@ApiResponse(responseCode = "201", headers = @Header(name =
   "Location"))`), and the problems their use case raises (`@ApiResponse(responseCode = "404", ref =
   "NotFound")`). Everything cross-cutting comes from `OpenApiConfig`; controllers don't repeat it.
4. **Off by default, on in `dev`**: `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` are
   `false` in `application.properties`, `true` in `application-dev.properties`. Swagger UI may not load
   another document from its URL (`query-config-enabled=false`).
5. **The published contract is `docs/openapi.yaml`**, generated from the real application and compared
   with the committed file by `OpenApiDocumentTests`: an API change fails the build until the file is
   regenerated (`-Dopenapi.update=true`), so every change of the contract is a reviewed diff.
6. **Bodies are JSON only**: no Jackson 2 converter serves the API (`MessageConvertersConfig`); another
   format is a `415`/`406` Problem.
7. **`GlobalExceptionHandler` is ordered first** (`@Order(HIGHEST_PRECEDENCE)`): with its catch-all, a
   library's advice can't take over part of the error handling.
8. Locked in by `OpenApiTests` (11, each naming what was documented before, several sending the documented
   request), `OpenApiDocumentTests`, `RequestBodyBindingTests` (YAML `415`/`406`) and
   `ProfileConfigurationTests` (prod: `404`; dev: served). Mutation checks: each of the corrections above,
   the handler order, the Swagger UI option, the prod default and the YAML converter each fail a test
   when removed. The tests disable springdoc's document cache: with it, PIT killed 0 of 66 mutants (the
   cached document predates them); without it, 64 of 64 after fixing what it found (`Idempotent-Replayed`
   was documented on every inline response, not only `2xx`).

## Consequences

**Positive**
- Frontend and client generators get an accurate contract, including errors, paging and retries.
- API changes are visible in review as a diff of `docs/openapi.yaml`.
- Production exposes no documentation endpoint and serves no Swagger UI JavaScript.
- Two side effects of the library (a second advice, a second body mapper) were found and closed.

**Negative / accepted trade-offs**
- springdoc 3.2.0: Jackson 2 on the classpath (used only to write the document) and a Swagger UI with
  known DOMPurify advisories, served in `dev` only, until the upgrade.
- `OpenApiConfig` knows the API's conventions a second time (limits, header names): it uses the same
  constants where they exist (`PageQuery.MAX_SIZE`, `IdempotencyKey.HEADER`...), and `OpenApiTests`
  checks the documented requests against the server.
- Response properties aren't marked `required`, although Jackson writes every property (`null`
  included): which ones may be `null` isn't declared in the code yet. Generated clients see them as
  optional. To be fixed with JSpecify `@NullMarked` DTO packages and springdoc's nullable support.
- Controllers must declare their success status and business errors; forgetting it documents `200`.
  Caught by review of the `docs/openapi.yaml` diff, not by a rule.

**Follow-ups**
- Upgrade to the springdoc 3.2.x line built on Boot 4.2 when it is a few weeks old.
- Contract tests: validate real responses against the document (ADR 0015 follow-up).
- With Spring Security: the bearer-token security scheme in the document.
- `required`/nullable response properties (above).

## References

- OpenAPI Specification 3.1; springdoc-openapi 3.2.0 release notes and properties; Swagger UI
  `queryConfigEnabled`
- Learning note: `learning/41-openapi-documentation.md`
