package com.shahabuddin.jobfinder.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;

/**
 * One entry of the {@code errors} array of a {@code validation-error} problem, in the shape RFC 9457
 * uses in its own example: a JSON Pointer ({@code "#/title"}) for body fields, or the name of a
 * query/path/header {@code parameter}, plus a machine-readable {@code code} and a human-readable
 * {@code detail}.
 *
 * <p>{@code code} is the stable part a client may branch on or translate: the Bean Validation
 * constraint name ({@code NotBlank}, {@code Size}, a custom {@code SalaryRange}...) or one of the
 * binding codes below (catalogue in docs/problems.md). {@code detail} is English text for developers.
 *
 * <p>The rejected value is deliberately <b>not</b> echoed back: it may be a password or other
 * personal data, and it would end up in client logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Violation(String pointer, String parameter, String code, String detail) {

    /** A body property the request type doesn't declare. */
    static final String UNKNOWN_PROPERTY = "UnknownProperty";
    /** A query parameter the endpoint doesn't declare. */
    static final String UNKNOWN_PARAMETER = "UnknownParameter";
    /** A value of the wrong JSON type or format (e.g. a string for a number, 2.5 for an integer). */
    static final String INVALID_TYPE = "InvalidType";
    /** A value that has the right type but that the request type rejected while being created. */
    static final String INVALID_VALUE = "InvalidValue";
    /** A required parameter or header is missing. */
    static final String REQUIRED = "Required";

    /** Stable order, so clients and tests see the same response for the same input. */
    static final Comparator<Violation> ORDER = Comparator
            .comparing((Violation v) -> v.pointer() != null ? v.pointer() : "")
            .thenComparing(v -> v.parameter() != null ? v.parameter() : "")
            .thenComparing(Violation::code)
            .thenComparing(Violation::detail);

    static Violation atPointer(String pointer, String code, String detail) {
        return new Violation(pointer, null, code, detail);
    }

    static Violation atParameter(String parameter, String code, String detail) {
        return new Violation(null, parameter, code, detail);
    }
}
