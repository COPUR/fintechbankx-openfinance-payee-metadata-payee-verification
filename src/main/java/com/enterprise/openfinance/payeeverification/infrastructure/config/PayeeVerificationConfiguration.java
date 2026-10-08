package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.application.VerifyPayeeService;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.port.out.PayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.domain.service.PayeeNameMatcher;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.CachingPayeeDirectoryAdapter;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.PayeeVerificationEventEnvelopeFactory;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.JpaPayeeDirectoryAdapter;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.JpaPayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.SpringDataPayeeDirectoryRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.SpringDataPayeeVerificationRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopReplayStore;
import com.enterprise.openfinance.payeeverification.infrastructure.security.JdbcDpopReplayStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Wires the use case to its adapters. */
@Configuration
@EnableScheduling
public class PayeeVerificationConfiguration {

    static final String SEED_LOCATION = "classpath:db/seed";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PayeeNameMatcher payeeNameMatcher(
        @Value("${openfinance.payee-verification.matching.close-match-threshold:85}") int closeMatchThreshold) {
        return new PayeeNameMatcher(closeMatchThreshold);
    }

    @Bean
    PayeeDirectoryPort payeeDirectory(SpringDataPayeeDirectoryRepository repository, Clock clock,
                                      @Value("${openfinance.payee-verification.directory-cache.ttl:PT30S}") Duration ttl,
                                      @Value("${openfinance.payee-verification.directory-cache.max-entries:10000}") int maxEntries) {
        return new CachingPayeeDirectoryAdapter(new JpaPayeeDirectoryAdapter(repository), ttl, maxEntries, clock);
    }

    @Bean
    PayeeVerificationEventEnvelopeFactory payeeVerificationEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new PayeeVerificationEventEnvelopeFactory(objectMapper);
    }

    @Bean
    PayeeVerificationRepository payeeVerificationRepository(SpringDataPayeeVerificationRepository verifications,
                                                            SpringDataOutboxRepository outbox,
                                                            PayeeVerificationEventEnvelopeFactory envelopes,
                                                            PlatformTransactionManager transactionManager) {
        return new JpaPayeeVerificationRepository(verifications, outbox, envelopes,
            new TransactionTemplate(transactionManager));
    }

    @Bean
    VerifyPayeeUseCase verifyPayeeUseCase(PayeeDirectoryPort directory, PayeeVerificationRepository verifications,
                                          PayeeNameMatcher matcher, Clock clock) {
        return new VerifyPayeeService(directory, verifications, matcher, clock, UUID::randomUUID);
    }

    @Bean
    DpopReplayStore dpopReplayStore(JdbcTemplate jdbc) {
        return new JdbcDpopReplayStore(jdbc);
    }

    @Bean
    DpopReplayPurge dpopReplayPurge(DpopReplayStore store, Clock clock) {
        return new DpopReplayPurge(store, clock);
    }

    /**
     * Sample directory rows (db/seed) are applied only when
     * PAYEE_DIRECTORY_SEED_ENABLED=true: local, dev and CI, never production.
     */
    @Bean
    FlywayConfigurationCustomizer payeeDirectorySeed(
        @Value("${openfinance.payee-verification.directory-seed.enabled:false}") boolean seedEnabled) {
        return configuration -> {
            if (!seedEnabled) {
                return;
            }
            List<String> locations = new ArrayList<>(Arrays.stream(configuration.getLocations())
                .map(Object::toString).toList());
            if (!locations.contains(SEED_LOCATION)) {
                locations.add(SEED_LOCATION);
            }
            configuration.locations(locations.toArray(String[]::new));
        };
    }

    static class DpopReplayPurge {
        private final DpopReplayStore store;
        private final Clock clock;

        DpopReplayPurge(DpopReplayStore store, Clock clock) {
            this.store = store;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${openfinance.dpop.replay-purge-interval:PT5M}")
        int purge() {
            return store.purgeExpired(clock.instant());
        }
    }
}
