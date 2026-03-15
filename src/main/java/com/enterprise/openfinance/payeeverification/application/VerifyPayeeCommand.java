package com.enterprise.openfinance.payeeverification.application;

public record VerifyPayeeCommand(String identification, String schemeName, String requestedName) {
}
