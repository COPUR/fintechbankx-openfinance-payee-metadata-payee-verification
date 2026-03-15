package com.enterprise.openfinance.payeeverification.domain;

public record PayeeProfile(String identification, String legalName, AccountStatus accountStatus) {
}
