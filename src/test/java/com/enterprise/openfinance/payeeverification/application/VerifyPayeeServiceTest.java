package com.enterprise.openfinance.payeeverification.application;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.enterprise.openfinance.payeeverification.domain.exception.IdempotencyKeyConflictException;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.AccountType;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifyPayeeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private static final AccountReference TAREQ = AccountReference.of("IBAN", "AE280330000000123456789");
    private static final AccountReference CLOSED = AccountReference.of("IBAN", "AE120330000000111111111");

    private final FakeDirectory directory = new FakeDirectory();
    private final FakeVerifications verifications = new FakeVerifications();
    private final AtomicInteger sequence = new AtomicInteger();
    private VerifyPayeeService service;

    @BeforeEach
    void setUp() {
        directory.put(new PayeeDirectoryEntry(TAREQ, "Al Tareq Trading LLC", AccountType.BUSINESS, AccountStatus.ACTIVE));
        directory.put(new PayeeDirectoryEntry(CLOSED, "Dormant Company LLC", AccountType.BUSINESS, AccountStatus.CLOSED));
        service = new VerifyPayeeService(directory, verifications, new PayeeNameMatcher(85),
            Clock.fixed(NOW, ZoneOffset.UTC), () -> new UUID(0, sequence.incrementAndGet()));
    }

    @Test
    void exactMatchIsRecordedWithItsEventAndHidesTheHolderName() {
        VerificationResult result = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tareq Trading LLC", "tpp-alpha", "ix-1"));

        assertThat(result.verification().outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(result.matchedName()).isNull();
        assertThat(result.replayed()).isFalse();
        assertThat(verifications.recordedEvents).containsExactly(new PayeeVerificationCompleted(
            new UUID(0, 1), "tpp-alpha", "ix-1", TAREQ.hash(), MatchOutcome.MATCH,
            VerificationReasonCode.EXACT_NAME_MATCH, NOW));
    }

    @Test
    void closeMatchReturnsTheHolderName() {
        VerificationResult result = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tariq Trading LLC", "tpp-alpha", "ix-2"));

        assertThat(result.verification().outcome()).isEqualTo(MatchOutcome.CLOSE_MATCH);
        assertThat(result.matchedName()).isEqualTo("Al Tareq Trading LLC");
    }

    @Test
    void closedAccountIsUnableToCheck() {
        VerificationResult result = service.verify(new VerifyPayeeCommand(CLOSED, "Dormant Company LLC", "tpp-alpha", "ix-3"));

        assertThat(result.verification().accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(result.verification().outcome()).isEqualTo(MatchOutcome.UNABLE_TO_CHECK);
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void unknownAccountIsUnableToCheck() {
        VerificationResult result = service.verify(new VerifyPayeeCommand(
            AccountReference.of("IBAN", "AE070331234567890123456"), "Anyone", "tpp-alpha", "ix-4"));

        assertThat(result.verification().accountStatus()).isEqualTo(AccountStatus.UNKNOWN);
        assertThat(result.verification().reasonCode()).isEqualTo(VerificationReasonCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void repeatedInteractionIdReturnsTheStoredDecisionWithoutASecondRecord() {
        VerificationResult first = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tariq Trading LLC", "tpp-alpha", "ix-5"));
        VerificationResult second = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tariq Trading LLC", "tpp-alpha", "ix-5"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.verification().verificationId()).isEqualTo(first.verification().verificationId());
        assertThat(second.matchedName()).isEqualTo("Al Tareq Trading LLC");
        assertThat(verifications.rows).hasSize(1);
        assertThat(verifications.recordedEvents).hasSize(1);
    }

    @Test
    void anotherTppUsingTheSameInteractionIdGetsItsOwnDecision() {
        VerificationResult alpha = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tareq Trading LLC", "tpp-alpha", "ix-6"));
        VerificationResult beta = service.verify(new VerifyPayeeCommand(TAREQ, "Random Corporation", "tpp-beta", "ix-6"));

        assertThat(beta.replayed()).isFalse();
        assertThat(beta.verification().verificationId()).isNotEqualTo(alpha.verification().verificationId());
        assertThat(beta.verification().outcome()).isEqualTo(MatchOutcome.NO_MATCH);
        assertThat(verifications.rows).hasSize(2);
    }

    @Test
    void reusingAnInteractionIdForAnotherAccountIsAConflict() {
        service.verify(new VerifyPayeeCommand(TAREQ, "Al Tareq Trading LLC", "tpp-alpha", "ix-7"));

        assertThatThrownBy(() -> service.verify(new VerifyPayeeCommand(CLOSED, "Dormant Company LLC", "tpp-alpha", "ix-7")))
            .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(verifications.rows).hasSize(1);
    }

    @Test
    void losingAConcurrentInsertReturnsTheWinnersDecision() {
        PayeeVerification winner = PayeeVerification.restore(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            "tpp-alpha", "ix-8", TAREQ.hash(), AccountStatus.ACTIVE, MatchOutcome.MATCH,
            VerificationReasonCode.EXACT_NAME_MATCH, 100, NOW.minusMillis(5));
        verifications.raceWinner = winner;

        VerificationResult result = service.verify(new VerifyPayeeCommand(TAREQ, "Al Tareq Trading LLC", "tpp-alpha", "ix-8"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.verification().verificationId()).isEqualTo(winner.verificationId());
    }

    private static final class FakeDirectory implements PayeeDirectoryPort {
        private final Map<AccountReference, PayeeDirectoryEntry> entries = new HashMap<>();

        void put(PayeeDirectoryEntry entry) {
            entries.put(entry.account(), entry);
        }

        @Override
        public Optional<PayeeDirectoryEntry> find(AccountReference account) {
            return Optional.ofNullable(entries.get(account));
        }
    }

    private static final class FakeVerifications implements PayeeVerificationRepository {
        private final Map<String, PayeeVerification> rows = new HashMap<>();
        private final List<PayeeVerificationCompleted> recordedEvents = new ArrayList<>();
        private PayeeVerification raceWinner;

        @Override
        public Optional<PayeeVerification> findByIdempotencyKey(String tppId, String interactionId) {
            return Optional.ofNullable(rows.get(tppId + "|" + interactionId));
        }

        @Override
        public PayeeVerification recordOrGetExisting(PayeeVerification verification) {
            if (raceWinner != null) {
                return raceWinner;
            }
            String key = verification.tppId() + "|" + verification.interactionId();
            PayeeVerification existing = rows.putIfAbsent(key, verification);
            if (existing != null) {
                return existing;
            }
            recordedEvents.addAll(verification.pendingEvents());
            verification.clearPendingEvents();
            return verification;
        }
    }
}
