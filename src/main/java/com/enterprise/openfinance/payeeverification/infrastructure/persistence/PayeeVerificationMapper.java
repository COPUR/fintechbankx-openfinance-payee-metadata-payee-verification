package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;

final class PayeeVerificationMapper {

    private PayeeVerificationMapper() {
    }

    static PayeeVerificationJpaEntity toRow(PayeeVerification v) {
        return new PayeeVerificationJpaEntity(v.verificationId(), v.tppId(), v.interactionId(),
            v.accountReferenceHash(), v.accountStatus().name(), v.outcome().name(), v.reasonCode().name(),
            (short) v.matchScore(), v.decidedAt());
    }

    static PayeeVerification toDomain(PayeeVerificationJpaEntity row) {
        return PayeeVerification.restore(row.getId(), row.getTppId(), row.getInteractionId(),
            row.getAccountReferenceHash(), AccountStatus.valueOf(row.getAccountStatus()),
            MatchOutcome.valueOf(row.getMatchOutcome()), VerificationReasonCode.valueOf(row.getReasonCode()),
            row.getMatchScore(), row.getCreatedAt());
    }
}
