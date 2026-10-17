package com.enterprise.openfinance.payeeverification.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * <p>One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering an aggregate's events. Consumers de-duplicate on
 * eventId, which makes the at-least-once delivery safe.
 *
 * <p>Failure policy, ADR-021 decision 4:
 * <ul>
 *   <li>Payload errors ({@link RecordTooLargeException}, {@link SerializationException},
 *       {@link InvalidTopicException}) can never succeed: the row is parked and the
 *       relay continues with the next row.</li>
 *   <li>Every other error (authorization, broker unavailable, timeouts, a producer
 *       that cannot be built, anything unclassified) stops the batch without marking
 *       any row, so ordering is kept, and the relay retries with exponential back-off.
 *       Such a row is never parked automatically, however long the error lasts; the
 *       oldest-pending-age gauge pages the owning squad, and only an operator may park
 *       the row by hand, with a reason (runbook, {@code park_outbox_event}).</li>
 * </ul>
 * Every parked row increments the counter {@code outbox.parked.events}
 * (outbox_parked_events_total{exception}) once: the relay's own parks with the
 * exception class, operator parks with exception="OperatorPark", counted on the
 * next run by the replica holding the relay lock. The platform alert
 * OutboxEventsParked fires on any increase.
 * Metrics carry the exception class only, never identifiers or messages.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6F6670766F7574L; // "ofpvout"
    static final String PARKED_BY_RELAY = "relay";
    static final String PARKED_COUNTER = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    private static final Set<Class<? extends Throwable>> PAYLOAD_ERRORS =
        Set.of(RecordTooLargeException.class, SerializationException.class, InvalidTopicException.class);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final MeterRegistry meters;

    private Duration backoff = Duration.ZERO;
    private Instant nextAttemptAt = Instant.MIN;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.meters = meters;
    }

    /**
     * @return number of events published in this run
     */
    public synchronized int relayOnce() {
        if (clock.instant().isBefore(nextAttemptAt)) {
            return 0;
        }
        Outcome outcome = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return new Outcome(0, false);
            }
            countOperatorParks();
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Outcome(sent, true);
                } catch (Exception e) {
                    Throwable cause = rootKafkaCause(e);
                    String errorClass = cause.getClass().getSimpleName();
                    failures(errorClass).increment();
                    if (isPayloadError(cause)) {
                        log.error("Outbox event {} cannot be published ({}); parked, relay continues", row.getEventId(), errorClass);
                        row.park(clock.instant(), errorClass, PARKED_BY_RELAY);
                        parked(errorClass).increment();
                        continue;
                    }
                    log.warn("Outbox relay stopped at event {} ({}); no row marked, retrying with back-off",
                        row.getEventId(), errorClass);
                    return new Outcome(sent, true);
                }
            }
            return new Outcome(sent, false);
        });
        if (outcome == null) {
            return 0;
        }
        if (outcome.stopped()) {
            backoff = backoff.isZero() ? INITIAL_BACKOFF : min(backoff.multipliedBy(2), MAX_BACKOFF);
            nextAttemptAt = clock.instant().plus(backoff);
        } else {
            backoff = Duration.ZERO;
            nextAttemptAt = Instant.MIN;
        }
        return outcome.sent();
    }

    /** Current back-off after consecutive stopped batches; zero when the relay is healthy. */
    synchronized Duration backoff() {
        return backoff;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    private Counter failures(String errorClass) {
        return Counter.builder("outbox.send.failures")
            .description("Outbox sends that failed, by exception class (ADR-021 decision 4)")
            .tag("exception", errorClass)
            .register(meters);
    }

    private Counter parked(String exceptionClass) {
        return Counter.builder(PARKED_COUNTER)
            .description("Outbox rows parked (payload error, or OperatorPark), one increment per row")
            .tag("exception", exceptionClass)
            .register(meters);
    }

    /** Operator parks happen outside the app; count each one once (this replica holds the relay lock). */
    private void countOperatorParks() {
        for (OutboxEventJpaEntity row : outbox.findUncountedParks()) {
            row.markParkCounted();
            parked(OPERATOR_PARK).increment();
            log.warn("Outbox event {} was parked by an operator", row.getEventId());
        }
    }

    /**
     * Unwraps the future's ExecutionException / CompletionException and Spring's
     * KafkaException wrappers (KafkaProducerException) down to the client error.
     */
    static Throwable rootKafkaCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current
            && (current instanceof ExecutionException
                || current instanceof CompletionException
                || current instanceof org.springframework.kafka.KafkaException)) {
            current = current.getCause();
        }
        return current;
    }

    static boolean isPayloadError(Throwable cause) {
        return PAYLOAD_ERRORS.stream().anyMatch(type -> type.isInstance(cause));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private record Outcome(int sent, boolean stopped) {
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
