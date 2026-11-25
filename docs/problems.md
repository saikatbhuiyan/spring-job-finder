# Problem types

Every error response of the Job Finder API is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
**Problem Details** document (`Content-Type: application/problem+json`):

```json
{
  "type": "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Job '42' was not found.",
  "instance": "/api/v1/jobs/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

| Member | Meaning |
|--------|---------|
| `type` | Identifies the kind of problem. **Compare on this**, not on `title` or `detail`. Missing (= `about:blank`) for generic HTTP errors, where `status` says it all |
| `title` | Short summary of the problem type; the same for every occurrence |
| `status` | The HTTP status code |
| `detail` | Explanation of this occurrence, safe to show to a user |
| `instance` | The request path that failed |
| `traceId` | The request's trace ID, also in the `X-Trace-Id` response header. **Quote it when reporting a problem**: it finds every log line of that request ([ADR 0013](adr/0013-request-correlation.md)) |

A `500` never contains internal details: `"detail": "An unexpected error occurred."`.

A `429 Too Many Requests` (rate limit exceeded) carries a `Retry-After` header: the number of seconds to
wait before retrying.

A `400 Bad Request` without a `type` means the request couldn't be read at all, before validation: for
example a query string that isn't valid percent-encoded UTF-8 (`?q=%zz`), a value without a parameter
name (`?=x`), or more than 1,000 parameters.

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

## idempotency-key-reused

`422 Unprocessable Content`. The `Idempotency-Key` was already used for a **different** request (another
method, path, query string or body). A key identifies one operation: use a new key for a new request.

## request-in-progress

`409 Conflict`, with `Retry-After: 1`. The first request with this `Idempotency-Key` is still being
processed. Retry the same request after the indicated seconds to get its result. Nothing needs to change
in the request.

## Idempotency keys

`POST` and `PATCH` requests can be made safe to retry with an `Idempotency-Key` header
([ADR 0014](adr/0014-idempotency-keys.md), following the IETF draft
`draft-ietf-httpapi-idempotency-key-header`):

```http
POST /api/v1/jobs HTTP/1.1
Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"
```

- **Format**: a quoted string (or the same characters unquoted) of **16 to 255** printable ASCII
  characters. Use a random UUID, a new one per operation. Anything else is a `validation-error` (code
  `InvalidValue`), never silently ignored. Endpoints documented as idempotent **require** a key (code
  `Required` without one).
- **Retry** with the same key and the **byte-identical** request: you get the first **successful (2xx)**
  response again (status, `Location`, body), with `Idempotent-Replayed: true`. The operation is not
  executed again.
- **Errors aren't kept**: after a `4xx` or `5xx` the key is free, so the same key can be used for the
  retry or the corrected request.
- **Lifetime**: a key is kept for **24 hours** after its response. A retry after that executes again.
- **Size**: a request with a key may have a body of at most 1 MB (`413` beyond it).
