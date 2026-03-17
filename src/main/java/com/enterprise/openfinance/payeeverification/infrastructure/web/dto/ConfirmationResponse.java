package com.enterprise.openfinance.payeeverification.infrastructure.web.dto;

public record ConfirmationResponse(Data Data) {
    public record Data(String AccountStatus, String NameMatched, String MatchedName) {
    }
}
