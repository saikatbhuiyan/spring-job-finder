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
    { "pointer": "#/company/name", "detail": "must not be blank" },
    { "pointer": "#/salary", "detail": "must be greater than 0" },
    { "pointer": "#/tags/1", "detail": "must not be blank" }
  ]
}
```

| Entry | Meaning |
|-------|---------|
| `pointer` | A [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) (as a URI fragment) to the field in the request body. `#` is the body as a whole |
| `parameter` | The name of a query, path or header parameter (e.g. `page_size`), used instead of `pointer` |
| `detail` | What's wrong with it |

Also used when the body contains a property the API doesn't accept (`"is not a known property"`) or a
value of the wrong type (`"has an invalid value or type"`). Rejected values are never echoed back.

## malformed-request-body

`400 Bad Request`. The request body is missing or is not valid JSON. Fix the syntax and retry.

## resource-not-found

`404 Not Found`. A resource the request refers to doesn't exist, e.g. an unknown job id. (An unknown
*URL* is a plain `404` without this type.)

## conflict

`409 Conflict`. The request conflicts with the current state of a resource, e.g. closing a job that is
already closed. Reload the resource and decide again; retrying the same request won't help.
