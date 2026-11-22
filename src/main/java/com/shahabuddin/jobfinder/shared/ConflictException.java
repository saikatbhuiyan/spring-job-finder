package com.shahabuddin.jobfinder.shared;

/**
 * The request conflicts with the current state of a resource (HTTP 409), e.g. closing a job that is
 * already closed, or registering a company name that is already taken.
 */
public non-sealed class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
