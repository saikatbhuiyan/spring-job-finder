package com.shahabuddin.jobfinder.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;

/**
 * One entry of the {@code errors} array of a {@code validation-error} problem, in the shape RFC 9457
 * uses in its own example: a JSON Pointer ({@code "#/title"}) for body fields, or the name of a
 * query/path/header {@code parameter}, plus a human-readable {@code detail}.
 *
 * <p>The rejected value is deliberately <b>not</b> echoed back: it may be a password or other
 * personal data, and it would end up in client logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Violation(String pointer, String parameter, String detail) {

    /** Stable order, so clients and tests see the same response for the same input. */
    static final Comparator<Violation> ORDER = Comparator
            .comparing((Violation v) -> v.pointer() != null ? v.pointer() : "")
            .thenComparing(v -> v.parameter() != null ? v.parameter() : "")
            .thenComparing(Violation::detail);

    static Violation atPointer(String pointer, String detail) {
        return new Violation(pointer, null, detail);
    }

    static Violation atParameter(String parameter, String detail) {
        return new Violation(null, parameter, detail);
    }
}
