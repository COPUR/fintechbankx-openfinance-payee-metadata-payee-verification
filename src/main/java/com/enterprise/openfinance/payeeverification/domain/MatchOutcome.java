package com.enterprise.openfinance.payeeverification.domain;

public enum MatchOutcome {
    MATCH("Match"),
    CLOSE_MATCH("CloseMatch"),
    NO_MATCH("NoMatch"),
    UNABLE_TO_CHECK("UnableToCheck");

    private final String apiValue;

    MatchOutcome(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
