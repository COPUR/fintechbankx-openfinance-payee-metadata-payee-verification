package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Row of sc_of_payee_verification.payee_directory_entry. Read-only for the
 * service: rows are loaded by db/import/import-payee-directory.sh (and later
 * by an accounts-event projection). holder_name is personal data.
 */
@Entity
@Immutable
@Table(name = "payee_directory_entry")
public class PayeeDirectoryEntryJpaEntity {

    @Id
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "scheme_name", nullable = false, length = 32)
    private String schemeName;

    @Column(name = "identification", nullable = false, length = 64)
    private String identification;

    @Column(name = "holder_name", nullable = false, length = 140)
    private String holderName;

    @Column(name = "account_type", nullable = false, length = 16)
    private String accountType;

    @Column(name = "account_status", nullable = false, length = 16)
    private String accountStatus;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayeeDirectoryEntryJpaEntity() {
    }

    PayeeDirectoryEntryJpaEntity(String schemeName, String identification, String holderName,
                                 String accountType, String accountStatus, Instant updatedAt) {
        this.schemeName = schemeName;
        this.identification = identification;
        this.holderName = holderName;
        this.accountType = accountType;
        this.accountStatus = accountStatus;
        this.updatedAt = updatedAt;
    }

    public String getSchemeName() { return schemeName; }
    public String getIdentification() { return identification; }
    public String getHolderName() { return holderName; }
    public String getAccountType() { return accountType; }
    public String getAccountStatus() { return accountStatus; }
    public Instant getUpdatedAt() { return updatedAt; }
}
