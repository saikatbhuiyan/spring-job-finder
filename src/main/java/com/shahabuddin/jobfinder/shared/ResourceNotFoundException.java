package com.shahabuddin.jobfinder.shared;

/**
 * The requested resource doesn't exist (HTTP 404). {@code non-sealed} so a module may define a more
 * specific type, e.g. {@code JobNotFoundException extends ResourceNotFoundException}.
 */
public non-sealed class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " '" + id + "' was not found.");
    }
}
