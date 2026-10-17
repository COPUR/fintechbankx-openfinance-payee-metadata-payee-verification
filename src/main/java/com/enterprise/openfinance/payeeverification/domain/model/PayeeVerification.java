package com.enterprise.openfinance.payeeverification.domain.model;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.enterprise.openfinance.payeeverification.domain.exception.IdempotencyKeyConflictException;
import com.enterprise.openfinance.payeeverification.domain.service.NameMatch;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate: one Confirmation of Payee decision. Immutable once decided
 * (insert-only); identified by verificationId and unique per (tppId,
 * interactionId). Holds no names and no raw account identification.
 */
public final class PayeeVerification {

    private final UUID verificationId;
    private final String tppId;
    private final String interactionId;
    private final String accountReferenceHash;
    private final AccountStatus accountStatus;
    private final MatchOutcome outcome;
    private final VerificationReasonCode reasonCode;
    private final int matchScore;
    private final Instant decidedAt;
    private final List<PayeeVerificationCompleted> pendingEvents = new ArrayList<>();

    private PayeeVerification(UUID verificationId, String tppId, String interactionId, String accountReferenceHash,
                              AccountStatus accountStatus, MatchOutcome outcome, VerificationReasonCode reasonCode,
                              int matchScore, Instant decidedAt) {
        this.verificationId = Objects.requireNonNull(verificationId, "verificationId");
        this.tppId = requireText(tppId, "tppId");
        this.interactionId = requireText(interactionId, "interactionId");
        this.accountReferenceHash = requireText(accountReferenceHash, "accountReferenceHash");
        this.accountStatus = Objects.requireNonNull(accountStatus, "accountStatus");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (matchScore < 0 || matchScore > 100) {
            throw new IllegalArgumentException("matchScore must be between 0 and 100");
        }
        this.matchScore = matchScore;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    /**
     * Decides a verification. Only an ACTIVE account is name-checked; closed,
     * deceased and unknown accounts are UNABLE_TO_CHECK so a TPP cannot use
     * the service to probe holder names of accounts that cannot be paid.
     */
    public static PayeeVerification decide(UUID verificationId, VerifyPayeeCommand command,
                                           Optional<PayeeDirectoryEntry> directoryEntry,
                                           PayeeNameMatcher matcher, Instant now) {
        directoryEntry.ifPresent(entry -> {
            if (!entry.account().equals(command.account())) {
                throw new IllegalArgumentException("directory entry belongs to a different account");
            }
        });

        AccountStatus status = directoryEntry.map(PayeeDirectoryEntry::accountStatus).orElse(AccountStatus.UNKNOWN);
        MatchOutcome outcome;
        VerificationReasonCode reason;
        int score = 0;
        switch (status) {
            case UNKNOWN -> {
                outcome = MatchOutcome.UNABLE_TO_CHECK;
                reason = VerificationReasonCode.ACCOUNT_NOT_FOUND;
            }
            case CLOSED -> {
                outcome = MatchOutcome.UNABLE_TO_CHECK;
                reason = VerificationReasonCode.ACCOUNT_CLOSED;
            }
            case DECEASED -> {
                outcome = MatchOutcome.UNABLE_TO_CHECK;
                reason = VerificationReasonCode.ACCOUNT_DECEASED;
            }
            default -> {
                NameMatch match = matcher.match(directoryEntry.orElseThrow().holderName(), command.requestedName());
                score = match.score();
                outcome = match.outcome();
                reason = switch (outcome) {
                    case MATCH -> VerificationReasonCode.EXACT_NAME_MATCH;
                    case CLOSE_MATCH -> VerificationReasonCode.CLOSE_NAME_MATCH;
                    default -> VerificationReasonCode.NAME_MISMATCH;
                };
            }
        }

        PayeeVerification verification = new PayeeVerification(verificationId, command.tppId(),
            command.interactionId(), command.account().hash(), status, outcome, reason, score, now);
        verification.pendingEvents.add(new PayeeVerificationCompleted(verificationId, command.tppId(),
            command.interactionId(), verification.accountReferenceHash, outcome, reason, now));
        return verification;
    }

    /** Rebuilds a stored decision; raises no events. */
    public static PayeeVerification restore(UUID verificationId, String tppId, String interactionId,
                                            String accountReferenceHash, AccountStatus accountStatus,
                                            MatchOutcome outcome, VerificationReasonCode reasonCode,
                                            int matchScore, Instant decidedAt) {
        return new PayeeVerification(verificationId, tppId, interactionId, accountReferenceHash, accountStatus,
            outcome, reasonCode, matchScore, decidedAt);
    }

    /**
     * A repeated request with the same idempotency key must be for the same
     * account; otherwise the key was reused and the request is rejected.
     */
    public void assertReplayOf(VerifyPayeeCommand command) {
        if (!accountReferenceHash.equals(command.account().hash())) {
            throw new IdempotencyKeyConflictException(interactionId);
        }
    }

    /** The holder name may be shown to the payer only for a close match. */
    public boolean disclosesHolderName() {
        return outcome == MatchOutcome.CLOSE_MATCH;
    }

    public List<PayeeVerificationCompleted> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public void clearPendingEvents() {
        pendingEvents.clear();
    }

    public UUID verificationId() { return verificationId; }
    public String tppId() { return tppId; }
    public String interactionId() { return interactionId; }
    public String accountReferenceHash() { return accountReferenceHash; }
    public AccountStatus accountStatus() { return accountStatus; }
    public MatchOutcome outcome() { return outcome; }
    public VerificationReasonCode reasonCode() { return reasonCode; }
    public int matchScore() { return matchScore; }
    public Instant decidedAt() { return decidedAt; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
