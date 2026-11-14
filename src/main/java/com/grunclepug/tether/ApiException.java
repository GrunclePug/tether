package com.grunclepug.tether;

/** A request the caller got wrong or the game can't do right now; reported as HTTP 400. */
final class ApiException extends RuntimeException {
    ApiException(String message) {
        super(message);
    }
}
