package com.enterprise.openfinance.payeeverification.infrastructure.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = inlineTransactions();
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        clock, 50, Duration.ofSeconds(1), Duration.ofDays(7), meters);

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aBrokerFailureStopsTheBatchWithoutMarkingAnyRow() {
        OutboxEventJpaEntity first = row("VER-1");
        OutboxEventJpaEntity second = row("VER-1");
        OutboxEventJpaEntity third = row("VER-1");
        batch(first, second, third);
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(sent())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertUntouched(second);
        assertUntouched(third);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    /** ADR-021 decision 4: an authorization error marks no row and stops the batch. */
    @Test
    void anAuthorizationErrorMarksNoRowAndStopsTheBatch() {
        OutboxEventJpaEntity first = row("VER-1");
        OutboxEventJpaEntity second = row("VER-2");
        batch(first, second);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed(
            new TopicAuthorizationException("Not authorized to access topics: [evt.of.payee.v1]")));

        assertThat(relay.relayOnce()).isZero();

        assertUntouched(first);
        assertUntouched(second);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        verify(outbox, never()).save(any());
        assertThat(failures("TopicAuthorizationException")).isEqualTo(1.0);
    }

    @Test
    void anAuthenticationErrorThrownBySendItselfMarksNoRow() {
        OutboxEventJpaEntity only = row("VER-1");
        batch(only);
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new SaslAuthenticationException("bad credentials"));

        assertThat(relay.relayOnce()).isZero();

        assertUntouched(only);
        assertThat(failures("SaslAuthenticationException")).isEqualTo(1.0);
    }

    @Test
    void aProducerThatCannotBeBuiltMarksNoRow() {
        OutboxEventJpaEntity only = row("VER-1");
        batch(only);
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new KafkaException("Failed to construct kafka producer"));

        assertThat(relay.relayOnce()).isZero();

        assertUntouched(only);
        assertThat(failures("KafkaException")).isEqualTo(1.0);
    }

    /**
     * A topic missing from the cluster (UnknownTopicOrPartition) or a send timeout is not
     * the event's fault: the batch stops with no row marked and the back-off starts.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UnknownTopicOrPartitionException", "TimeoutException"})
    void aMissingTopicOrATimeoutStopsTheBatchMarksNoRowAndStartsTheBackOff(String error) {
        OutboxEventJpaEntity first = row("VER-1");
        OutboxEventJpaEntity second = row("VER-2");
        batch(first, second);
        RuntimeException cause = error.equals("TimeoutException")
            ? new TimeoutException("Expiring 1 record(s)")
            : new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("evt.of.payee.v1");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed(cause));

        assertThat(relay.relayOnce()).isZero();

        assertThat(OutboxRelay.isPayloadError(cause)).isFalse();
        assertUntouched(first);
        assertUntouched(second);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(relay.backoff()).isEqualTo(OutboxRelay.INITIAL_BACKOFF);
        assertThat(failures(error)).isEqualTo(1.0);
        assertThat(meters.find("outbox.parked.events").counters()).isEmpty();
    }

    /** ADR-021 decision 4 has no time ceiling: a retryable error never parks a row, however long it lasts. */
    @Test
    void aRetryableErrorThatPersistsPastTwentyFourHoursStillDoesNotPark() {
        OutboxEventJpaEntity only = row("VER-1");
        batch(only);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed(new TimeoutException("Topic not present in metadata")));

        for (int hour = 0; hour <= 25; hour++) {
            relay.relayOnce();
            clock.advance(Duration.ofHours(1));
        }

        assertUntouched(only);
        assertThat(failures("TimeoutException")).isEqualTo(26.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RecordTooLargeException", "SerializationException", "InvalidTopicException"})
    void aPayloadErrorParksTheRowAndTheNextRowIsSent(String error) {
        OutboxEventJpaEntity poison = row("VER-1");
        OutboxEventJpaEntity next = row("VER-2");
        batch(poison, next);
        RuntimeException cause = switch (error) {
            case "RecordTooLargeException" -> new RecordTooLargeException("The message is 2000000 bytes");
            case "SerializationException" -> new SerializationException("Can't convert value");
            default -> new InvalidTopicException("Invalid topic");
        };
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(failed(cause))
            .thenReturn(sent());

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getParkedReason()).isEqualTo(error);
        assertThat(poison.getParkedBy()).isEqualTo(OutboxRelay.PARKED_BY_RELAY);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
        assertThat(failures(error)).isEqualTo(1.0);
    }

    @Test
    void aPayloadErrorIsCountedAsAParkedEventTaggedWithItsClass() {
        OutboxEventJpaEntity poison = row("VER-1");
        batch(poison);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed(new RecordTooLargeException("2000000 bytes")));

        relay.relayOnce();

        assertThat(meters.get("outbox.parked.events").tag("exception", "RecordTooLargeException").counter().count())
            .isEqualTo(1.0);
        assertThat(poison.isParkCounted()).as("the relay's own park is counted when it parks").isTrue();
        assertThat(meters.find("outbox.parked.events").tag("exception", "OperatorPark").counter()).isNull();
    }

    @Test
    void anOperatorParkIsCountedOnceByTheRelay() {
        OutboxEventJpaEntity operatorParked = row("VER-OP");
        // What park_outbox_event() leaves behind: parked with a reason and the login, park_counted false.
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedAt", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedReason", "INC-1 dropped");
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedBy", "ops");
        batch();
        when(outbox.findUncountedParks()).thenReturn(List.of(operatorParked)).thenReturn(List.of());

        relay.relayOnce();
        relay.relayOnce();

        assertThat(operatorParked.isParkCounted()).isTrue();
        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count())
            .as("counted once, not on every run").isEqualTo(1.0);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void parksAreNotCountedByAReplicaWithoutTheRelayLock() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        relay.relayOnce();

        verify(outbox, never()).findUncountedParks();
        assertThat(meters.find("outbox.parked.events").counters()).isEmpty();
    }

    @Test
    void afterAFailureTheRelayBacksOffAndASuccessResetsIt() {
        OutboxEventJpaEntity only = row("VER-1");
        batch(only);
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(failed(new TimeoutException("expired")))
            .thenReturn(failed(new TimeoutException("expired")))
            .thenReturn(sent());

        relay.relayOnce();                       // fails: back off 1 s
        relay.relayOnce();                       // inside the back-off: nothing is tried
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        relay.relayOnce();                       // fails again: back off 2 s
        clock.advance(Duration.ofSeconds(1));
        relay.relayOnce();                       // still inside the 2 s back-off
        verify(kafka, times(2)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.backoff()).isEqualTo(Duration.ZERO);
    }

    @Test
    void theFailureCounterIsTaggedByExceptionClassOnly() {
        batch(row("VER-1"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed(
            new TopicAuthorizationException("Not authorized to access topics: [evt.of.payee.v1]")));

        relay.relayOnce();

        var counter = meters.get("outbox.send.failures").counter();
        assertThat(counter.getId().getTags()).extracting(t -> t.getKey()).containsExactly("exception");
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("VER-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.of.payee.v1");
        assertThat(record.key()).isEqualTo("VER-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("OpenFinance.PayeeVerification.VerificationCompleted.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
    }

    @Test
    void aFactoryRowGoesToTheAggregateTopicKeyedByVerificationIdWithTheRequiredTextHeaders() throws Exception {
        PayeeVerificationEventEnvelopeFactory factory =
            new PayeeVerificationEventEnvelopeFactory(new com.fasterxml.jackson.databind.ObjectMapper());
        UUID verificationId = UUID.fromString("7f6c1a52-1d7e-4f3c-9a51-0c2b8d4e6f10");
        OutboxEventJpaEntity stored = factory.toOutboxRow(new com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted(
            verificationId, "tpp-alpha", "ix-agg", "a".repeat(64),
            com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome.MATCH,
            com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode.EXACT_NAME_MATCH, NOW));

        ProducerRecord<String, String> record = OutboxRelay.toRecord(stored);

        com.fasterxml.jackson.databind.JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.value());
        assertThat(record.topic()).isEqualTo("evt.of.payee.v1");
        assertThat(record.key()).isEqualTo(verificationId.toString()).isEqualTo(envelope.path("aggregateId").asText());
        assertThat(header(record, "eventType")).isEqualTo(envelope.path("eventType").asText())
            .isEqualTo("OpenFinance.PayeeVerification.VerificationCompleted.v1");
        assertThat(header(record, "eventId")).isEqualTo(envelope.path("eventId").asText());
        assertThat(header(record, "correlationId")).isEqualTo(envelope.path("correlationId").asText()).isEqualTo("ix-agg");
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private void batch(OutboxEventJpaEntity... rows) {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(rows));
    }

    private double failures(String exception) {
        var counter = meters.find("outbox.send.failures").tag("exception", exception).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static void assertUntouched(OutboxEventJpaEntity row) {
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getParkedReason()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture((SendResult<String, String>) null);
    }

    private static CompletableFuture<SendResult<String, String>> failed(Throwable cause) {
        // KafkaTemplate completes the future with a KafkaProducerException wrapping the client error.
        return CompletableFuture.failedFuture(new KafkaProducerException(null, "Failed to send", cause));
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

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "PayeeVerification", aggregateId, 0L,
            "OpenFinance.PayeeVerification.VerificationCompleted.v1", "evt.of.payee.v1", "{}", "corr-9", NOW);
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
