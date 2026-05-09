package com.codeleon.common.exception;

/**
 * Thrown when an authenticated caller is denied an action because of
 * an authorization rule (e.g. only the room owner can archive a room).
 * Maps to HTTP 403 via {@link GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
