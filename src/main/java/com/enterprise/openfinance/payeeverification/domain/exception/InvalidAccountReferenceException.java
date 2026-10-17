package com.enterprise.openfinance.payeeverification.domain.exception;

/** The account scheme or identification supplied by the TPP is not usable. */
public class InvalidAccountReferenceException extends RuntimeException {
    public InvalidAccountReferenceException(String message) {
        super(message);
    }
}
