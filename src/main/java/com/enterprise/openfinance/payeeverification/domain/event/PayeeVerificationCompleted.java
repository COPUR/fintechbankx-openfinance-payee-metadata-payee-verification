package com.enterprise.openfinance.payeeverification.domain.event;

import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;

import java.time.Instant;
import java.util.UUID;

/**
 * A payee verification was decided. Ids and facts only: no typed name, no
 * holder name, no raw account identification.
 */
public record PayeeVerificationCompleted(
    UUID verificationId,
    String tppId,
    String interactionId,
    String accountReferenceHash,
    MatchOutcome outcome,
    VerificationReasonCode reasonCode,
    Instant occurredAt
) {
}
