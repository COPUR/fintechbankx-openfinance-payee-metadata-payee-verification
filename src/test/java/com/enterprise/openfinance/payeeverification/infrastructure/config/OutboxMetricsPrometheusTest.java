package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxEventJpaEntity;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The platform's parked-metric ruling: counter outbox_parked_events_total{exception}
 * (one increment per parked row) and gauge outbox_parked_rows (rows parked now)
 * must both register in one Prometheus registry. A gauge and a counter sharing the
 * base name outbox_parked_events would be refused, so the gauge has its own name.
 */
class OutboxMetricsPrometheusTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void parkedCounterAndParkedRowsGaugeAreScrapedUnderTheirPlatformNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(outbox.countByParkedAtIsNotNull()).thenReturn(2L);
        OutboxConfiguration configuration = new OutboxConfiguration();
        configuration.outboxPendingGauge(registry, outbox);
        configuration.outboxOldestPendingAgeGauge(registry, outbox);
        configuration.outboxParkedGauge(registry, outbox);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, inlineTransactions(), Clock.fixed(NOW, ZoneOffset.UTC), 50,
            Duration.ofSeconds(1), Duration.ofDays(7), registry);
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(anyInt())).thenReturn(List.of(new OutboxEventJpaEntity(UUID.randomUUID(),
            "PayeeVerification", "VER-1", 0L, "OpenFinance.PayeeVerification.VerificationCompleted.v1",
            "evt.of.payee.v1", "{}", "corr-1", NOW)));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
            new KafkaProducerException(null, "Failed to send", new RecordTooLargeException("2000000 bytes"))));

        relay.relayOnce();

        String scrape = registry.scrape();
        assertThat(scrape)
            .contains("outbox_parked_events_total{exception=\"RecordTooLargeException\"} 1.0")
            .contains("outbox_parked_rows{service=\"svc-of-payee-verification\"} 2.0")
            .contains("outbox_send_failures_total{exception=\"RecordTooLargeException\"} 1.0")
            .contains("outbox_pending_events{")
            .contains("outbox_oldest_pending_age_seconds{");
        assertThat(scrape).doesNotContain("outbox_parked_events{");
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
