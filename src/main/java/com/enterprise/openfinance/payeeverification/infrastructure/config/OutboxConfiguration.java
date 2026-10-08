package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

    /**
     * Backlog of events not yet on Kafka (Prometheus outbox_pending_events).
     * Alert on growth: the relay or the brokers are down while decisions keep
     * being recorded.
     */
    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNull)
            .description("Payee verification events written to the outbox but not yet published to Kafka")
            .tag("service", "svc-of-payee-verification")
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Disable with openfinance.outbox.relay.enabled=false
     * (tests, or a dedicated relay deployment).
     */
    @Configuration
    @ConditionalOnProperty(name = "openfinance.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${openfinance.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${openfinance.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${openfinance.outbox.retention:P7D}") Duration retention) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${openfinance.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${openfinance.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
