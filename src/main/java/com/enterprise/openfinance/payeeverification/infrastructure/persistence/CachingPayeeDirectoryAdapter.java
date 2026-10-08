package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, bounded in-process cache in front of the directory (ported
 * from the monolith's InMemoryPayeeDirectoryCacheAdapter). Only hits are
 * cached, so a newly imported account is visible immediately; a status change
 * is visible after at most the TTL.
 */
public class CachingPayeeDirectoryAdapter implements PayeeDirectoryPort {

    private final PayeeDirectoryPort delegate;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<AccountReference, CacheItem> cache = new ConcurrentHashMap<>();

    public CachingPayeeDirectoryAdapter(PayeeDirectoryPort delegate, Duration ttl, int maxEntries, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.delegate = delegate;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @Override
    public Optional<PayeeDirectoryEntry> find(AccountReference account) {
        Instant now = clock.instant();
        CacheItem item = cache.get(account);
        if (item != null && item.expiresAt().isAfter(now)) {
            return Optional.of(item.entry());
        }
        if (item != null) {
            cache.remove(account, item);
        }
        Optional<PayeeDirectoryEntry> loaded = delegate.find(account);
        loaded.ifPresent(entry -> {
            if (cache.size() >= maxEntries) {
                evictOne();
            }
            cache.put(account, new CacheItem(entry, now.plus(ttl)));
        });
        return loaded;
    }

    int size() {
        return cache.size();
    }

    private void evictOne() {
        Iterator<AccountReference> keys = cache.keySet().iterator();
        if (keys.hasNext()) {
            cache.remove(keys.next());
        }
    }

    private record CacheItem(PayeeDirectoryEntry entry, Instant expiresAt) {
    }
}
