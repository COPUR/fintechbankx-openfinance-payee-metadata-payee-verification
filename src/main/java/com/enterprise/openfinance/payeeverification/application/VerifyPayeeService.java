package com.enterprise.openfinance.payeeverification.application;

import com.enterprise.openfinance.payeeverification.application.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.application.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.PayeeNameMatcher;
import com.enterprise.openfinance.payeeverification.domain.PayeeProfile;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class VerifyPayeeService implements VerifyPayeeUseCase {

    private final PayeeDirectoryPort payeeDirectoryPort;
    private final PayeeNameMatcher payeeNameMatcher = new PayeeNameMatcher();

    public VerifyPayeeService(PayeeDirectoryPort payeeDirectoryPort) {
        this.payeeDirectoryPort = payeeDirectoryPort;
    }

    @Override
    public VerifyPayeeResult verify(VerifyPayeeCommand command) {
        Optional<PayeeProfile> candidate = payeeDirectoryPort.findByIdentification(command.identification());
        if (candidate.isEmpty()) {
            return new VerifyPayeeResult(AccountStatus.CLOSED, MatchOutcome.UNABLE_TO_CHECK, null);
        }

        PayeeProfile profile = candidate.get();
        if (profile.accountStatus() != AccountStatus.ACTIVE) {
            return new VerifyPayeeResult(profile.accountStatus(), MatchOutcome.UNABLE_TO_CHECK, null);
        }

        MatchOutcome outcome = payeeNameMatcher.match(profile.legalName(), command.requestedName());
        String matchedName = outcome == MatchOutcome.NO_MATCH ? null : profile.legalName();
        return new VerifyPayeeResult(profile.accountStatus(), outcome, matchedName);
    }
}
