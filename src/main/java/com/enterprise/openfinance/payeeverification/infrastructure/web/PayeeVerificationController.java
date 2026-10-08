package com.enterprise.openfinance.payeeverification.infrastructure.web;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.infrastructure.security.TppIdentity;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ConfirmationRequest;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ConfirmationResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open-finance/v1/confirmation-of-payee")
public class PayeeVerificationController {

    static final int MAX_INTERACTION_ID_LENGTH = 128;

    private final VerifyPayeeUseCase verifyPayeeUseCase;

    public PayeeVerificationController(VerifyPayeeUseCase verifyPayeeUseCase) {
        this.verifyPayeeUseCase = verifyPayeeUseCase;
    }

    @PostMapping("/confirmation")
    ResponseEntity<ConfirmationResponse> confirm(@AuthenticationPrincipal Jwt token,
                                                 @RequestHeader("X-FAPI-Interaction-ID") String interactionId,
                                                 @Valid @RequestBody ConfirmationRequest request) {
        if (interactionId.length() > MAX_INTERACTION_ID_LENGTH) {
            throw new IllegalArgumentException("X-FAPI-Interaction-ID must be at most 128 characters");
        }
        VerificationResult result = verifyPayeeUseCase.verify(new VerifyPayeeCommand(
                AccountReference.of(request.Data().SchemeName(), request.Data().Identification()),
                request.Data().Name(),
                TppIdentity.clientId(token),
                interactionId));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .body(new ConfirmationResponse(new ConfirmationResponse.Data(
                        apiStatus(result.verification().accountStatus()),
                        apiOutcome(result.verification().outcome()),
                        result.matchedName())));
    }

    /**
     * The published enum is Active, Closed, Deceased. An unknown account is
     * reported as Closed, which also avoids telling a caller whether an
     * account number exists.
     */
    static String apiStatus(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> "Active";
            case DECEASED -> "Deceased";
            case CLOSED, UNKNOWN -> "Closed";
        };
    }

    static String apiOutcome(MatchOutcome outcome) {
        return switch (outcome) {
            case MATCH -> "Match";
            case CLOSE_MATCH -> "CloseMatch";
            case NO_MATCH -> "NoMatch";
            case UNABLE_TO_CHECK -> "UnableToCheck";
        };
    }
}
