package com.enterprise.openfinance.payeeverification.domain.port.in;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;

public interface VerifyPayeeUseCase {

    VerificationResult verify(VerifyPayeeCommand command);
}
