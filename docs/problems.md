# Problem types

Every error response of the Job Finder API is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
**Problem Details** document (`Content-Type: application/problem+json`):

```json
{
  "type": "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Job '42' was not found.",
  "instance": "/api/v1/jobs/42"
}
```

| Member | Meaning |
|--------|---------|
| `type` | Identifies the kind of problem. **Compare on this**, not on `title` or `detail`. Missing (= `about:blank`) for generic HTTP errors, where `status` says it all |
| `title` | Short summary of the problem type; the same for every occurrence |
| `status` | The HTTP status code |
| `detail` | Explanation of this occurrence, safe to show to a user |
| `instance` | The request path that failed |

A `500` never contains internal details: `"detail": "An unexpected error occurred."`.

The types defined by this API are listed below. Their URIs are part of the API contract and never change.

## validation-error

`400 Bad Request`. The request is well-formed, but fields or parameters are invalid. The `errors` array
lists **every** violation, sorted by location:

```json
{
  "type": "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#validation-error",
  "title": "Invalid request",
  "status": 400,
  "detail": "The request contains invalid fields or parameters.",
  "instance": "/api/v1/jobs",
  "errors": [
    { "pointer": "#/company/name", "code": "NotBlank", "detail": "must not be blank" },
    { "pointer": "#/salary", "code": "Positive", "detail": "must be greater than 0" },
    { "parameter": "page_size", "code": "InvalidType", "detail": "has an invalid value or type" }
  ]
}
```

| Entry | Meaning |
|-------|---------|
| `pointer` | A [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) (as a URI fragment) to the field in the request body. `#` is the body as a whole |
| `parameter` | The name of a query, path or header parameter (e.g. `page_size`, `Idempotency-Key`), used instead of `pointer` |
| `code` | What kind of violation it is. **Stable: branch on or translate this**, not `detail` |
| `detail` | What's wrong with it, in English, for developers |

Codes:

| `code` | Meaning |
|--------|---------|
| A constraint name: `NotNull`, `NotBlank`, `Size`, `Positive`, `Max`, `Email`, ... or one defined by the API | The value breaks that rule. See the endpoint's documentation for the limits |
| `UnknownProperty` | The body contains a property the endpoint doesn't accept |
| `UnknownParameter` | The query string contains a parameter the endpoint doesn't accept (often a typo in a filter name) |
| `InvalidType` | The value has the wrong JSON type or format: a string for a number, `2.5` for an integer, a number for a string, an unknown enum value. Values are never converted |
| `InvalidValue` | The value has the right type but was rejected as a whole |
| `Required` | A required parameter or header is missing |

Rejected values are never echoed back.

## malformed-request-body

`400 Bad Request`. The request body is missing or is not valid JSON. Fix the syntax and retry.

## resource-not-found

`404 Not Found`. A resource the request refers to doesn't exist, e.g. an unknown job id. (An unknown
*URL* is a plain `404` without this type.)

## conflict

`409 Conflict`. The request conflicts with the current state of a resource, e.g. closing a job that is
already closed. Reload the resource and decide again; retrying the same request won't help.
