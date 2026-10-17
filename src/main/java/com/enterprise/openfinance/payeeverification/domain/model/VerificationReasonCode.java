package com.enterprise.openfinance.payeeverification.domain.model;

/** Why a verification ended with its outcome. Stable codes; part of the event contract. */
public enum VerificationReasonCode {
    EXACT_NAME_MATCH,
    CLOSE_NAME_MATCH,
    NAME_MISMATCH,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_CLOSED,
    ACCOUNT_DECEASED
}
