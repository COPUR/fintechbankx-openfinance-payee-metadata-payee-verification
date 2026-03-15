package com.enterprise.openfinance.payeeverification.infrastructure.web;

import com.enterprise.openfinance.payeeverification.application.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.application.VerifyPayeeResult;
import com.enterprise.openfinance.payeeverification.application.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ConfirmationRequest;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ConfirmationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open-finance/v1/confirmation-of-payee")
public class PayeeVerificationController {

    private final VerifyPayeeUseCase verifyPayeeUseCase;

    public PayeeVerificationController(VerifyPayeeUseCase verifyPayeeUseCase) {
        this.verifyPayeeUseCase = verifyPayeeUseCase;
    }

    @PostMapping("/confirmation")
    ResponseEntity<ConfirmationResponse> confirm(@Valid @RequestBody ConfirmationRequest request) {
        VerifyPayeeResult result = verifyPayeeUseCase.verify(new VerifyPayeeCommand(
                request.Data().Identification(),
                request.Data().SchemeName(),
                request.Data().Name()
        ));

        return ResponseEntity.ok(new ConfirmationResponse(
                new ConfirmationResponse.Data(
                        result.accountStatus().apiValue(),
                        result.matchOutcome().apiValue(),
                        result.matchedName()
                )
        ));
    }
}
