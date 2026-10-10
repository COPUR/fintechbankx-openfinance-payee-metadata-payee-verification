package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.AccountType;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.PayeeVerificationEventEnvelopeFactory;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class JpaPayeeVerificationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private static final AccountReference ACCOUNT = AccountReference.of("IBAN", "AE280330000000123456789");

    private final SpringDataPayeeVerificationRepository rows = mock(SpringDataPayeeVerificationRepository.class);
    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final JpaPayeeVerificationRepository repository = new JpaPayeeVerificationRepository(rows, outbox,
        new PayeeVerificationEventEnvelopeFactory(new ObjectMapper()), inlineTransactions());

    @Test
    void recordsDecisionAndOneOutboxRowThenClearsPendingEvents() {
        PayeeVerification decided = decided("ix-1");

        PayeeVerification recorded = repository.recordOrGetExisting(decided);

        assertThat(recorded).isSameAs(decided);
        assertThat(recorded.pendingEvents()).isEmpty();
        ArgumentCaptor<PayeeVerificationJpaEntity> row = ArgumentCaptor.forClass(PayeeVerificationJpaEntity.class);
        verify(rows).saveAndFlush(row.capture());
        assertThat(row.getValue().isNew()).isTrue();
        assertThat(row.getValue().getId()).isEqualTo(decided.verificationId());
        assertThat(row.getValue().getAccountReferenceHash()).isEqualTo(ACCOUNT.hash());
        assertThat(row.getValue().getMatchOutcome()).isEqualTo("MATCH");
        assertThat(row.getValue().getReasonCode()).isEqualTo("EXACT_NAME_MATCH");
        assertThat(row.getValue().getMatchScore()).isEqualTo((short) 100);
        ArgumentCaptor<List<OutboxEventJpaEntity>> events = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAllAndFlush(events.capture());
        assertThat(events.getValue()).singleElement()
            .extracting(OutboxEventJpaEntity::getAggregateId).isEqualTo(decided.verificationId().toString());
    }

    @Test
    void duplicateKeyReturnsTheEarlierDecision() {
        PayeeVerification decided = decided("ix-2");
        PayeeVerification earlier = PayeeVerification.restore(UUID.randomUUID(), "tpp-alpha", "ix-2", ACCOUNT.hash(),
            AccountStatus.ACTIVE, MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 100, NOW.minusSeconds(1));
        when(rows.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_payee_verification_idempotency"));
        when(rows.findByTppIdAndInteractionId("tpp-alpha", "ix-2"))
            .thenReturn(Optional.of(PayeeVerificationMapper.toRow(earlier)));

        PayeeVerification recorded = repository.recordOrGetExisting(decided);

        assertThat(recorded.verificationId()).isEqualTo(earlier.verificationId());
        assertThat(recorded.decidedAt()).isEqualTo(earlier.decidedAt());
        assertThat(recorded.pendingEvents()).isEmpty();
    }

    @Test
    void anIntegrityViolationThatIsNotADuplicateIsRethrown() {
        when(rows.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ck_payee_verification_hash"));
        when(rows.findByTppIdAndInteractionId("tpp-alpha", "ix-3")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.recordOrGetExisting(decided("ix-3")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storedRowsMapBackToTheDomain() {
        PayeeVerification original = PayeeVerification.restore(UUID.randomUUID(), "tpp-alpha", "ix-4", ACCOUNT.hash(),
            AccountStatus.CLOSED, MatchOutcome.UNABLE_TO_CHECK, VerificationReasonCode.ACCOUNT_CLOSED, 0, NOW);
        when(rows.findByTppIdAndInteractionId("tpp-alpha", "ix-4"))
            .thenReturn(Optional.of(PayeeVerificationMapper.toRow(original)));

        PayeeVerification loaded = repository.findByIdempotencyKey("tpp-alpha", "ix-4").orElseThrow();

        assertThat(loaded.accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(loaded.reasonCode()).isEqualTo(VerificationReasonCode.ACCOUNT_CLOSED);
        assertThat(loaded.tppId()).isEqualTo("tpp-alpha");
        assertThat(loaded.interactionId()).isEqualTo("ix-4");
        assertThat(loaded.decidedAt()).isEqualTo(NOW);
    }

    private static PayeeVerification decided(String interactionId) {
        return PayeeVerification.decide(UUID.randomUUID(),
            new VerifyPayeeCommand(ACCOUNT, "Al Tareq Trading LLC", "tpp-alpha", interactionId),
            Optional.of(new PayeeDirectoryEntry(ACCOUNT, "Al Tareq Trading LLC", AccountType.BUSINESS, AccountStatus.ACTIVE)),
            new PayeeNameMatcher(85), NOW);
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
