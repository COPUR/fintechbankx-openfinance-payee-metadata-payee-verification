package com.enterprise.openfinance.payeeverification.application.port.in;

import com.enterprise.openfinance.payeeverification.application.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.application.VerifyPayeeResult;

public interface VerifyPayeeUseCase {
    VerifyPayeeResult verify(VerifyPayeeCommand command);
}
