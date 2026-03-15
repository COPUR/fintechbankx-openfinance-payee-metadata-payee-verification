package com.enterprise.openfinance.payeeverification.application;

import com.enterprise.openfinance.payeeverification.domain.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.MatchOutcome;

public record VerifyPayeeResult(AccountStatus accountStatus, MatchOutcome matchOutcome, String matchedName) {
}
