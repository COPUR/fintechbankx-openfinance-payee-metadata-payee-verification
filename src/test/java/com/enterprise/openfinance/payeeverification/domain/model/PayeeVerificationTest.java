package com.enterprise.openfinance.payeeverification.domain.model;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.enterprise.openfinance.payeeverification.domain.exception.IdempotencyKeyConflictException;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayeeVerificationTest {

    private static final UUID ID = UUID.fromString("7f0c3c1e-4b8e-4d2a-9a51-0c1d2e3f4a5b");
    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private static final AccountReference ACCOUNT = AccountReference.of("IBAN", "AE280330000000123456789");
    private static final PayeeNameMatcher MATCHER = new PayeeNameMatcher(85);

    private static PayeeDirectoryEntry entry(AccountStatus status) {
        return new PayeeDirectoryEntry(ACCOUNT, "Al Tareq Trading LLC", AccountType.BUSINESS, status);
    }

    private static VerifyPayeeCommand command(String name) {
        return new VerifyPayeeCommand(ACCOUNT, name, "tpp-alpha", "ix-0001");
    }

    @Test
    void exactNameOnAnActiveAccountIsAMatchAndRaisesOneCompletedEvent() {
        PayeeVerification v = PayeeVerification.decide(ID, command("AL TAREQ TRADING LLC"),
            Optional.of(entry(AccountStatus.ACTIVE)), MATCHER, NOW);

        assertThat(v.outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(v.reasonCode()).isEqualTo(VerificationReasonCode.EXACT_NAME_MATCH);
        assertThat(v.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(v.matchScore()).isEqualTo(100);
        assertThat(v.accountReferenceHash()).isEqualTo(ACCOUNT.hash());
        assertThat(v.pendingEvents()).containsExactly(new PayeeVerificationCompleted(
            ID, "tpp-alpha", "ix-0001", ACCOUNT.hash(), MatchOutcome.MATCH,
            VerificationReasonCode.EXACT_NAME_MATCH, NOW));
    }

    @Test
    void nearNameIsACloseMatchWithItsScore() {
        PayeeVerification v = PayeeVerification.decide(ID, command("Al Tariq Trading LLC"),
            Optional.of(entry(AccountStatus.ACTIVE)), MATCHER, NOW);

        assertThat(v.outcome()).isEqualTo(MatchOutcome.CLOSE_MATCH);
        assertThat(v.reasonCode()).isEqualTo(VerificationReasonCode.CLOSE_NAME_MATCH);
        assertThat(v.matchScore()).isEqualTo(95);
        assertThat(v.disclosesHolderName()).isTrue();
    }

    @Test
    void differentNameIsNoMatchAndNeverDisclosesTheHolderName() {
        PayeeVerification v = PayeeVerification.decide(ID, command("Random Corporation"),
            Optional.of(entry(AccountStatus.ACTIVE)), MATCHER, NOW);

        assertThat(v.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
        assertThat(v.reasonCode()).isEqualTo(VerificationReasonCode.NAME_MISMATCH);
        assertThat(v.disclosesHolderName()).isFalse();
    }

    @Test
    void closedAndDeceasedAccountsAreUnableToCheckWithoutComparingNames() {
        PayeeVerification closed = PayeeVerification.decide(ID, command("Al Tareq Trading LLC"),
            Optional.of(entry(AccountStatus.CLOSED)), MATCHER, NOW);
        PayeeVerification deceased = PayeeVerification.decide(ID, command("Al Tareq Trading LLC"),
            Optional.of(entry(AccountStatus.DECEASED)), MATCHER, NOW);

        assertThat(closed.outcome()).isEqualTo(MatchOutcome.UNABLE_TO_CHECK);
        assertThat(closed.reasonCode()).isEqualTo(VerificationReasonCode.ACCOUNT_CLOSED);
        assertThat(closed.matchScore()).isZero();
        assertThat(deceased.reasonCode()).isEqualTo(VerificationReasonCode.ACCOUNT_DECEASED);
        assertThat(deceased.accountStatus()).isEqualTo(AccountStatus.DECEASED);
    }

    @Test
    void unknownAccountIsUnableToCheck() {
        PayeeVerification v = PayeeVerification.decide(ID, command("Anyone"), Optional.empty(), MATCHER, NOW);

        assertThat(v.outcome()).isEqualTo(MatchOutcome.UNABLE_TO_CHECK);
        assertThat(v.reasonCode()).isEqualTo(VerificationReasonCode.ACCOUNT_NOT_FOUND);
        assertThat(v.accountStatus()).isEqualTo(AccountStatus.UNKNOWN);
        assertThat(v.disclosesHolderName()).isFalse();
    }

    @Test
    void directoryEntryForAnotherAccountIsRejected() {
        PayeeDirectoryEntry other = new PayeeDirectoryEntry(AccountReference.of("IBAN", "AE770330000000987654321"),
            "Atlas Services LLC", AccountType.BUSINESS, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> PayeeVerification.decide(ID, command("Atlas Services LLC"), Optional.of(other), MATCHER, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoredDecisionCarriesNoNewEvents() {
        PayeeVerification v = PayeeVerification.restore(ID, "tpp-alpha", "ix-0001", ACCOUNT.hash(),
            AccountStatus.ACTIVE, MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 100, NOW);

        assertThat(v.pendingEvents()).isEmpty();
        assertThat(v.verificationId()).isEqualTo(ID);
        assertThat(v.tppId()).isEqualTo("tpp-alpha");
        assertThat(v.interactionId()).isEqualTo("ix-0001");
        assertThat(v.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void replayIsAllowedForTheSameAccountOnly() {
        PayeeVerification v = PayeeVerification.decide(ID, command("Al Tareq Trading LLC"),
            Optional.of(entry(AccountStatus.ACTIVE)), MATCHER, NOW);

        v.assertReplayOf(command("Al Tareq Trading LLC"));
        VerifyPayeeCommand otherAccount = new VerifyPayeeCommand(
            AccountReference.of("IBAN", "AE770330000000987654321"), "Atlas Services LLC", "tpp-alpha", "ix-0001");

        assertThatThrownBy(() -> v.assertReplayOf(otherAccount))
            .isInstanceOf(IdempotencyKeyConflictException.class)
            .hasMessageContaining("ix-0001");
    }

    @Test
    void clearingEventsAfterTheyAreRecorded() {
        PayeeVerification v = PayeeVerification.decide(ID, command("Al Tareq Trading LLC"),
            Optional.of(entry(AccountStatus.ACTIVE)), MATCHER, NOW);

        v.clearPendingEvents();

        assertThat(v.pendingEvents()).isEmpty();
    }

    @Test
    void commandRequiresTppAndInteractionId() {
        assertThatThrownBy(() -> new VerifyPayeeCommand(ACCOUNT, "Name", " ", "ix-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyPayeeCommand(ACCOUNT, "Name", "tpp", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyPayeeCommand(null, "Name", "tpp", "ix"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyPayeeCommand(ACCOUNT, "  ", "tpp", "ix"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandToStringDoesNotLeakTheTypedName() {
        assertThat(command("Al Tareq Trading LLC").toString()).doesNotContain("Tareq");
    }

    @Test
    void directoryEntryToStringDoesNotLeakTheHolderName() {
        assertThat(entry(AccountStatus.ACTIVE).toString()).doesNotContain("Tareq");
    }
}
