package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.AccountType;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingPayeeDirectoryAdapterTest {

    private static final AccountReference A = AccountReference.of("IBAN", "AE280330000000123456789");
    private static final AccountReference B = AccountReference.of("IBAN", "AE770330000000987654321");

    private final CountingDirectory directory = new CountingDirectory();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));

    @Test
    void hitsAreServedFromCacheUntilTheTtlExpires() {
        directory.entries.put(A, entry(A));
        CachingPayeeDirectoryAdapter cache = new CachingPayeeDirectoryAdapter(directory, Duration.ofSeconds(30), 10, clock);

        cache.find(A);
        cache.find(A);
        assertThat(directory.lookups).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        cache.find(A);
        assertThat(directory.lookups).isEqualTo(2);
    }

    @Test
    void missesAreNotCachedSoANewlyImportedAccountIsVisibleAtOnce() {
        CachingPayeeDirectoryAdapter cache = new CachingPayeeDirectoryAdapter(directory, Duration.ofSeconds(30), 10, clock);

        assertThat(cache.find(A)).isEmpty();
        directory.entries.put(A, entry(A));

        assertThat(cache.find(A)).isPresent();
    }

    @Test
    void sizeIsBounded() {
        directory.entries.put(A, entry(A));
        directory.entries.put(B, entry(B));
        CachingPayeeDirectoryAdapter cache = new CachingPayeeDirectoryAdapter(directory, Duration.ofSeconds(30), 1, clock);

        cache.find(A);
        cache.find(B);

        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new CachingPayeeDirectoryAdapter(directory, Duration.ZERO, 1, clock))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingPayeeDirectoryAdapter(directory, null, 1, clock))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingPayeeDirectoryAdapter(directory, Duration.ofSeconds(1), 0, clock))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static PayeeDirectoryEntry entry(AccountReference account) {
        return new PayeeDirectoryEntry(account, "Holder", AccountType.BUSINESS, AccountStatus.ACTIVE);
    }

    private static final class CountingDirectory implements PayeeDirectoryPort {
        private final Map<AccountReference, PayeeDirectoryEntry> entries = new HashMap<>();
        private int lookups;

        @Override
        public Optional<PayeeDirectoryEntry> find(AccountReference account) {
            lookups++;
            return Optional.ofNullable(entries.get(account));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
