package com.enterprise.openfinance.payeeverification.domain.model;

/**
 * One account of the payee directory (projection of core-banking account
 * data). The holder name is personal data: compare it, never log or publish it.
 */
public record PayeeDirectoryEntry(
    AccountReference account,
    String holderName,
    AccountType accountType,
    AccountStatus accountStatus
) {
    public PayeeDirectoryEntry {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        if (holderName == null || holderName.isBlank()) {
            throw new IllegalArgumentException("holderName is required");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        if (accountStatus == null || accountStatus == AccountStatus.UNKNOWN) {
            throw new IllegalArgumentException("accountStatus must be ACTIVE, CLOSED or DECEASED");
        }
        holderName = holderName.trim();
    }

    @Override
    public String toString() {
        return "PayeeDirectoryEntry[" + account + ", " + accountType + ", " + accountStatus + "]";
    }
}
