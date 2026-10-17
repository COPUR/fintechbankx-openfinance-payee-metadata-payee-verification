package com.enterprise.openfinance.payeeverification.domain.exception;

/** The TPP reused an interaction id for a different account. */
public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String interactionId) {
        super("X-FAPI-Interaction-ID " + interactionId + " was already used for a different account");
    }
}
