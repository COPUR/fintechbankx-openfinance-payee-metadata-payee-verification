package com.enterprise.openfinance.payeeverification.infrastructure.outbox;

import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps PayeeVerificationCompleted to the public envelope of
 * api/asyncapi/svc-of-payee-verification.yaml. One topic per aggregate (ADR-019):
 * evt.of.payee.v1, keyed by verificationId; the eventType (envelope and record
 * header) names the event. Ids and facts only.
 */
public class PayeeVerificationEventEnvelopeFactory {

    public static final String PRODUCER = "svc-of-payee-verification";
    public static final String AGGREGATE_TYPE = "PayeeVerification";
    public static final String TOPIC = "evt.of.payee.v1";
    public static final String EVENT_TYPE = "OpenFinance.PayeeVerification.VerificationCompleted.v1";
    /** Decisions are insert-only, so every decision is version 0. */
    static final long AGGREGATE_VERSION = 0L;

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    private final ObjectMapper objectMapper;

    public PayeeVerificationEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(PayeeVerificationCompleted event) {
        UUID eventId = UUID.randomUUID();
        String aggregateId = event.verificationId().toString();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verificationId", aggregateId);
        data.put("tppId", event.tppId());
        data.put("accountReferenceHash", event.accountReferenceHash());
        data.put("outcome", event.outcome().name());
        data.put("reasonCode", event.reasonCode().name());
        data.put("occurredAt", event.occurredAt().toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", AGGREGATE_VERSION);
        envelope.put("correlationId", event.interactionId());
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", data);

        return new OutboxEventJpaEntity(eventId, AGGREGATE_TYPE, aggregateId, AGGREGATE_VERSION,
            EVENT_TYPE, TOPIC, toJson(envelope), event.interactionId(), event.occurredAt(),
            traceparent(MDC.get("traceId"), MDC.get("spanId")));
    }

    /** W3C traceparent from the Micrometer Tracing MDC entries, or null when the request is not traced. */
    static String traceparent(String traceId, String spanId) {
        if (traceId == null || spanId == null || !TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + EVENT_TYPE + " envelope", e);
        }
    }
}
