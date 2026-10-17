package com.enterprise.openfinance.payeeverification.domain.model;

/** Status of the payee account as known to the directory projection. */
public enum AccountStatus {
    ACTIVE,
    CLOSED,
    DECEASED,
    /** No directory entry for the account. */
    UNKNOWN;

    public boolean canReceivePayments() {
        return this == ACTIVE;
    }
}
