package com.enterprise.openfinance.payeeverification.domain.model;

import com.enterprise.openfinance.payeeverification.domain.service.NameMatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainInvariantsTest {

    private static final AccountReference ACCOUNT = AccountReference.of("IBAN", "AE280330000000123456789");
    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

    @Test
    void onlyActiveAccountsCanReceivePayments() {
        assertThat(AccountStatus.ACTIVE.canReceivePayments()).isTrue();
        assertThat(AccountStatus.CLOSED.canReceivePayments()).isFalse();
        assertThat(AccountStatus.DECEASED.canReceivePayments()).isFalse();
        assertThat(AccountStatus.UNKNOWN.canReceivePayments()).isFalse();
    }

    @Test
    void directoryEntryRequiresEveryFieldAndAKnownStatus() {
        assertThatThrownBy(() -> new PayeeDirectoryEntry(null, "Name", AccountType.PERSONAL, AccountStatus.ACTIVE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayeeDirectoryEntry(ACCOUNT, " ", AccountType.PERSONAL, AccountStatus.ACTIVE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayeeDirectoryEntry(ACCOUNT, "Name", null, AccountStatus.ACTIVE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayeeDirectoryEntry(ACCOUNT, "Name", AccountType.PERSONAL, AccountStatus.UNKNOWN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayeeDirectoryEntry(ACCOUNT, "Name", AccountType.PERSONAL, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PayeeDirectoryEntry(ACCOUNT, "  Name  ", AccountType.PERSONAL, AccountStatus.ACTIVE).holderName())
            .isEqualTo("Name");
    }

    @Test
    void resultDropsTheHolderNameUnlessItIsACloseMatch() {
        PayeeVerification match = verification(MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 100);
        PayeeVerification close = verification(MatchOutcome.CLOSE_MATCH, VerificationReasonCode.CLOSE_NAME_MATCH, 90);

        assertThat(new VerificationResult(match, "Al Tareq Trading LLC", false).matchedName()).isNull();
        assertThat(new VerificationResult(close, "Al Tareq Trading LLC", true).matchedName()).isEqualTo("Al Tareq Trading LLC");
        assertThat(new VerificationResult(close, "Al Tareq Trading LLC", true).toString()).doesNotContain("Tareq");
        assertThatThrownBy(() -> new VerificationResult(null, null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedDecisionRejectsInvalidValues() {
        assertThatThrownBy(() -> verification(MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 101))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verification(MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayeeVerification.restore(UUID.randomUUID(), " ", "ix", ACCOUNT.hash(),
            AccountStatus.ACTIVE, MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 100, NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("tppId is required");
        assertThatThrownBy(() -> PayeeVerification.restore(null, "tpp", "ix", ACCOUNT.hash(),
            AccountStatus.ACTIVE, MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, 100, NOW))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nameMatchScoreIsAPercentage() {
        assertThatThrownBy(() -> new NameMatch(101, MatchOutcome.MATCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameMatch(-1, MatchOutcome.NO_MATCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameMatch(50, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static PayeeVerification verification(MatchOutcome outcome, VerificationReasonCode reason, int score) {
        return PayeeVerification.restore(UUID.randomUUID(), "tpp-alpha", "ix-1", ACCOUNT.hash(), AccountStatus.ACTIVE,
            outcome, reason, score, NOW);
    }
}
