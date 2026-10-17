package com.enterprise.openfinance.payeeverification.domain.port.out;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;

import java.util.Optional;

/** Read side of the payee directory projection. */
public interface PayeeDirectoryPort {

    Optional<PayeeDirectoryEntry> find(AccountReference account);
}
