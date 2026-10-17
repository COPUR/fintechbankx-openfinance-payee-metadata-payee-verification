package com.enterprise.openfinance.payeeverification.infrastructure.security;

/** A DPoP proof (RFC 9449) or the token binding was rejected. */
public class DpopValidationException extends RuntimeException {
    public DpopValidationException(String message) {
        super(message);
    }
}
