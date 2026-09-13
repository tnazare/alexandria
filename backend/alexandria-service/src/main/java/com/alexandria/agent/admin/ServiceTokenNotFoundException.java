package com.alexandria.agent.admin;

/**
 * Thrown when a service token cannot be found within the caller's organization (unknown id, or an
 * id belonging to another organization). Maps to HTTP 404 in the global exception handler.
 */
public class ServiceTokenNotFoundException extends RuntimeException {

    public ServiceTokenNotFoundException(String message) {
        super(message);
    }
}
