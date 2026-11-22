package com.shahabuddin.jobfinder.shared;

/**
 * Base type of the business failures a use case can report: "this job doesn't exist", "this job is
 * already closed". Feature modules throw (or subclass) the permitted types; the web layer maps each
 * one to an HTTP status (see docs/adr/0006-global-exception-handling.md).
 *
 * <p>The hierarchy is <b>sealed</b>: the exception handler switches over it exhaustively, so adding a
 * new kind of failure without deciding its HTTP mapping is a compile error, not a silent 500.
 *
 * <p>Messages are shown to API clients as the Problem Details {@code detail}, so they must be safe
 * and meaningful for a client: no internal ids of other users, no SQL, no stack traces.
 */
public abstract sealed class DomainException extends RuntimeException
        permits ResourceNotFoundException, ConflictException {

    protected DomainException(String message) {
        super(message);
    }
}
