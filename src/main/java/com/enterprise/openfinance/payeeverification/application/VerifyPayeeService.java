package com.enterprise.openfinance.payeeverification.application;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Verify-payee use case: replay a stored decision for a repeated
 * (tppId, interactionId), otherwise look the account up, let the aggregate
 * decide, and record decision plus event atomically.
 */
public class VerifyPayeeService implements VerifyPayeeUseCase {

    private final PayeeDirectoryPort directory;
    private final PayeeVerificationRepository verifications;
    private final PayeeNameMatcher matcher;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public VerifyPayeeService(PayeeDirectoryPort directory, PayeeVerificationRepository verifications,
                              PayeeNameMatcher matcher, Clock clock, Supplier<UUID> ids) {
        this.directory = directory;
        this.verifications = verifications;
        this.matcher = matcher;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public VerificationResult verify(VerifyPayeeCommand command) {
        Optional<PayeeVerification> earlier = verifications.findByIdempotencyKey(command.tppId(), command.interactionId());
        if (earlier.isPresent()) {
            return replay(earlier.get(), command);
        }

        Optional<PayeeDirectoryEntry> entry = directory.find(command.account());
        PayeeVerification decided = PayeeVerification.decide(ids.get(), command, entry, matcher, clock.instant());
        PayeeVerification recorded = verifications.recordOrGetExisting(decided);
        if (!recorded.verificationId().equals(decided.verificationId())) {
            // A concurrent request with the same key won the insert.
            return replay(recorded, command);
        }
        return new VerificationResult(recorded, holderName(entry), false);
    }

    private VerificationResult replay(PayeeVerification earlier, VerifyPayeeCommand command) {
        earlier.assertReplayOf(command);
        Optional<PayeeDirectoryEntry> entry = earlier.disclosesHolderName()
            ? directory.find(command.account())
            : Optional.empty();
        return new VerificationResult(earlier, holderName(entry), true);
    }

    private static String holderName(Optional<PayeeDirectoryEntry> entry) {
        return entry.map(PayeeDirectoryEntry::holderName).orElse(null);
    }
}
