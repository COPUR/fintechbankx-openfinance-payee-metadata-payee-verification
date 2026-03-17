package com.enterprise.openfinance.payeeverification.domain;

public enum AccountStatus {
    ACTIVE("Active"),
    CLOSED("Closed"),
    DECEASED("Deceased");

    private final String apiValue;

    AccountStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
