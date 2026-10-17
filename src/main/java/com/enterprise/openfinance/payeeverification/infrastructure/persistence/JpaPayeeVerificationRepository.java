package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.PayeeVerificationEventEnvelopeFactory;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Writes the decision row and its outbox rows in one transaction. The unique
 * key (tpp_id, interaction_id) makes a repeated or concurrent request with the
 * same idempotency key a no-op that returns the first decision.
 */
public class JpaPayeeVerificationRepository implements PayeeVerificationRepository {

    private final SpringDataPayeeVerificationRepository verifications;
    private final SpringDataOutboxRepository outbox;
    private final PayeeVerificationEventEnvelopeFactory envelopes;
    private final TransactionTemplate transactions;

    public JpaPayeeVerificationRepository(SpringDataPayeeVerificationRepository verifications,
                                          SpringDataOutboxRepository outbox,
                                          PayeeVerificationEventEnvelopeFactory envelopes,
                                          TransactionTemplate transactions) {
        this.verifications = verifications;
        this.outbox = outbox;
        this.envelopes = envelopes;
        this.transactions = transactions;
    }

    @Override
    public Optional<PayeeVerification> findByIdempotencyKey(String tppId, String interactionId) {
        return verifications.findByTppIdAndInteractionId(tppId, interactionId).map(PayeeVerificationMapper::toDomain);
    }

    @Override
    public PayeeVerification recordOrGetExisting(PayeeVerification verification) {
        try {
            transactions.executeWithoutResult(tx -> {
                verifications.saveAndFlush(PayeeVerificationMapper.toRow(verification));
                List<OutboxEventJpaEntity> rows = verification.pendingEvents().stream()
                    .map(envelopes::toOutboxRow)
                    .toList();
                outbox.saveAllAndFlush(rows);
            });
        } catch (DataIntegrityViolationException duplicate) {
            return findByIdempotencyKey(verification.tppId(), verification.interactionId())
                .orElseThrow(() -> duplicate);
        }
        verification.clearPendingEvents();
        return verification;
    }
}
