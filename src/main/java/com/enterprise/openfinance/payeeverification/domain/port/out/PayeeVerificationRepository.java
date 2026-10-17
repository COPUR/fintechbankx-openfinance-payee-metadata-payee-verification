package com.enterprise.openfinance.payeeverification.domain.port.out;

import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;

import java.util.Optional;

/** Insert-only store of verification decisions. */
public interface PayeeVerificationRepository {

    Optional<PayeeVerification> findByIdempotencyKey(String tppId, String interactionId);

    /**
     * Records the decision and its pending events (transactional outbox) in
     * one transaction. If a decision with the same (tppId, interactionId) was
     * recorded first, nothing is written and that earlier decision is returned.
     */
    PayeeVerification recordOrGetExisting(PayeeVerification verification);
}
