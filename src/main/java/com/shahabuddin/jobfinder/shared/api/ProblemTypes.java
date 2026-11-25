package com.shahabuddin.jobfinder.shared.api;

import java.net.URI;

/**
 * The Problem Details {@code type} URIs this API defines (RFC 9457 §3.1.1). Clients identify an
 * error by its {@code type}, so these URIs are part of the API contract: never change them.
 * Each one resolves to its documentation in {@code docs/problems.md}.
 *
 * <p>Generic HTTP errors (404 unknown path, 405, 500...) keep the default {@code about:blank}, where
 * the status code alone says what happened.
 */
final class ProblemTypes {

    private static final String BASE = "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#";

    /** The request is well-formed JSON, but fields or parameters are invalid; see {@code errors}. */
    static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");

    /** The request body is missing or isn't valid JSON. */
    static final URI MALFORMED_REQUEST_BODY = URI.create(BASE + "malformed-request-body");

    /** A resource referenced by the request doesn't exist. */
    static final URI RESOURCE_NOT_FOUND = URI.create(BASE + "resource-not-found");

    /** The request conflicts with the current state of a resource. */
    static final URI CONFLICT = URI.create(BASE + "conflict");

    /** An {@code Idempotency-Key} was sent again with a different request (ADR 0014). */
    static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");

    /** The request with this {@code Idempotency-Key} is still being processed (ADR 0014). */
    static final URI REQUEST_IN_PROGRESS = URI.create(BASE + "request-in-progress");

    private ProblemTypes() {
    }
}
