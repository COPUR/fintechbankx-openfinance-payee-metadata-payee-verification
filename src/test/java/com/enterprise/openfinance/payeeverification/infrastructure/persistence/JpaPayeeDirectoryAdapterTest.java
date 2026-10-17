package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.AccountType;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaPayeeDirectoryAdapterTest {

    private final SpringDataPayeeDirectoryRepository repository = mock(SpringDataPayeeDirectoryRepository.class);
    private final JpaPayeeDirectoryAdapter adapter = new JpaPayeeDirectoryAdapter(repository);

    @Test
    void looksUpByNormalisedSchemeAndIdentification() {
        PayeeDirectoryEntryJpaEntity row = new PayeeDirectoryEntryJpaEntity("IBAN", "AE280330000000123456789",
            "Al Tareq Trading LLC", "BUSINESS", "DECEASED", Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findBySchemeNameAndIdentification("IBAN", "AE280330000000123456789")).thenReturn(Optional.of(row));

        PayeeDirectoryEntry entry = adapter.find(AccountReference.of("iban", "ae28 0330 0000 0012 3456 789")).orElseThrow();

        assertThat(entry.account()).isEqualTo(AccountReference.of("IBAN", "AE280330000000123456789"));
        assertThat(entry.holderName()).isEqualTo("Al Tareq Trading LLC");
        assertThat(entry.accountType()).isEqualTo(AccountType.BUSINESS);
        assertThat(entry.accountStatus()).isEqualTo(AccountStatus.DECEASED);
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void unknownAccountIsEmpty() {
        assertThat(adapter.find(AccountReference.of("IBAN", "AE070331234567890123456"))).isEmpty();
    }
}
