package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.AccountType;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;

import java.util.Optional;

/** PayeeDirectoryPort over the payee_directory_entry projection table. */
public class JpaPayeeDirectoryAdapter implements PayeeDirectoryPort {

    private final SpringDataPayeeDirectoryRepository repository;

    public JpaPayeeDirectoryAdapter(SpringDataPayeeDirectoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PayeeDirectoryEntry> find(AccountReference account) {
        return repository.findBySchemeNameAndIdentification(account.schemeName(), account.identification())
            .map(JpaPayeeDirectoryAdapter::toDomain);
    }

    static PayeeDirectoryEntry toDomain(PayeeDirectoryEntryJpaEntity row) {
        return new PayeeDirectoryEntry(
            AccountReference.of(row.getSchemeName(), row.getIdentification()),
            row.getHolderName(),
            AccountType.valueOf(row.getAccountType()),
            AccountStatus.valueOf(row.getAccountStatus()));
    }
}
