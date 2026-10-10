package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_of_payee_verification.payee_verification: one insert-only
 * decision. No typed name, no holder name, no raw account identification.
 */
@Entity
@Immutable
@Table(name = "payee_verification")
public class PayeeVerificationJpaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "verification_id")
    private UUID verificationId;

    @Column(name = "tpp_id", nullable = false, length = 128, updatable = false)
    private String tppId;

    @Column(name = "interaction_id", nullable = false, length = 128, updatable = false)
    private String interactionId;

    @Column(name = "account_reference_hash", nullable = false, length = 64, updatable = false)
    private String accountReferenceHash;

    @Column(name = "account_status", nullable = false, length = 16, updatable = false)
    private String accountStatus;

    @Column(name = "match_outcome", nullable = false, length = 16, updatable = false)
    private String matchOutcome;

    @Column(name = "reason_code", nullable = false, length = 32, updatable = false)
    private String reasonCode;

    @Column(name = "match_score", nullable = false, updatable = false)
    private short matchScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected PayeeVerificationJpaEntity() {
    }

    PayeeVerificationJpaEntity(UUID verificationId, String tppId, String interactionId, String accountReferenceHash,
                               String accountStatus, String matchOutcome, String reasonCode, short matchScore,
                               Instant createdAt) {
        this.verificationId = verificationId;
        this.tppId = tppId;
        this.interactionId = interactionId;
        this.accountReferenceHash = accountReferenceHash;
        this.accountStatus = accountStatus;
        this.matchOutcome = matchOutcome;
        this.reasonCode = reasonCode;
        this.matchScore = matchScore;
        this.createdAt = createdAt;
        this.isNew = true;
    }

    @Override
    public UUID getId() { return verificationId; }

    @Override
    public boolean isNew() { return isNew; }

    public String getTppId() { return tppId; }
    public String getInteractionId() { return interactionId; }
    public String getAccountReferenceHash() { return accountReferenceHash; }
    public String getAccountStatus() { return accountStatus; }
    public String getMatchOutcome() { return matchOutcome; }
    public String getReasonCode() { return reasonCode; }
    public short getMatchScore() { return matchScore; }
    public Instant getCreatedAt() { return createdAt; }
}
