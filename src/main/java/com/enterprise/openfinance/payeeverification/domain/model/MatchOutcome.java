package com.enterprise.openfinance.payeeverification.domain.model;

/** Name-match outcome returned to the TPP. */
public enum MatchOutcome {
    MATCH,
    CLOSE_MATCH,
    NO_MATCH,
    UNABLE_TO_CHECK
}
