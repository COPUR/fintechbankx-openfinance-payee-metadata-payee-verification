package com.enterprise.openfinance.payeeverification.infrastructure.security;

public class MissingSecurityHeaderException extends RuntimeException {
    public MissingSecurityHeaderException(String message) {
        super(message);
    }
}
