package com.shahabuddin.jobfinder.shared.api;

import java.util.List;

/**
 * Query parameters that our own binding code (e.g. {@link PageQueryArgumentResolver}) rejected.
 * Answered by {@link GlobalExceptionHandler} as a {@code validation-error} listing every violation.
 * A client error, used for control flow: no stack trace is captured.
 */
final class InvalidRequestParametersException extends RuntimeException {

    private final List<Violation> violations;

    InvalidRequestParametersException(List<Violation> violations) {
        super("Invalid request parameters: " + violations, null, false, false);
        this.violations = List.copyOf(violations);
    }

    List<Violation> violations() {
        return violations;
    }
}
