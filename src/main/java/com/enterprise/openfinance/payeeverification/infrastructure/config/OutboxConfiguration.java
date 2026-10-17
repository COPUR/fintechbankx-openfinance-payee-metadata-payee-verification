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
     * Backlog of events not yet on Kafka (Prometheus outbox_pending_events),
     * parked rows excluded.
     */
    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
            .description("Payee verification events written to the outbox but not yet published to Kafka")
            .tag("service", "svc-of-payee-verification")
            .register(registry);
    }

    /**
     * Age of the oldest row the relay still has to send (Prometheus
     * outbox_oldest_pending_age_seconds). ADR-021 decision 4: a non-payload error
     * never parks a row, so this gauge is what pages the owning squad.
     */
    @Bean
    Gauge outboxOldestPendingAgeGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.oldest.pending.age.seconds", outbox, SpringDataOutboxRepository::oldestPendingAgeSeconds)
            .description("Age of the oldest outbox row that is neither published nor parked")
            .tag("service", "svc-of-payee-verification")
            .register(registry);
    }

    /**
     * Rows parked right now, by the relay (payload error) or by an operator
     * (Prometheus outbox_parked_rows). Not named outbox.parked.events: that is the
     * counter outbox_parked_events_total{exception} (OutboxRelay, platform alert
     * OutboxEventsParked), and Prometheus refuses a gauge and a counter sharing a base name.
     */
    @Bean
    Gauge outboxParkedGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.parked.rows", outbox, SpringDataOutboxRepository::countByParkedAtIsNotNull)
            .description("Outbox rows taken out of the relay; see parked_reason and parked_by")
            .tag("service", "svc-of-payee-verification")
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Off unless openfinance.outbox.relay.enabled=true
     * (OUTBOX_RELAY_ENABLED), which the chart sets explicitly.
     */
    @Configuration
    @ConditionalOnProperty(name = "openfinance.outbox.relay.enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${openfinance.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${openfinance.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${openfinance.outbox.retention:P7D}") Duration retention,
                                MeterRegistry meters) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention, meters);
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
