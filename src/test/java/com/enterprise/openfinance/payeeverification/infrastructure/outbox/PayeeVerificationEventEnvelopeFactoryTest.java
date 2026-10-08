package com.enterprise.openfinance.payeeverification.infrastructure.outbox;

import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayeeVerificationEventEnvelopeFactoryTest {

    private static final UUID ID = UUID.fromString("7f0c3c1e-4b8e-4d2a-9a51-0c1d2e3f4a5b");
    private static final Instant AT = Instant.parse("2026-10-08T09:30:00Z");
    private static final String HASH = AccountReference.of("IBAN", "AE280330000000123456789").hash();

    private final ObjectMapper json = new ObjectMapper();
    private final PayeeVerificationEventEnvelopeFactory factory = new PayeeVerificationEventEnvelopeFactory(json);

    @Test
    void envelopeFollowsTheAsyncApiContract() throws Exception {
        OutboxEventJpaEntity row = factory.toOutboxRow(new PayeeVerificationCompleted(ID, "tpp-alpha", "ix-1", HASH,
            MatchOutcome.CLOSE_MATCH, VerificationReasonCode.CLOSE_NAME_MATCH, AT));

        assertThat(row.getTopic()).isEqualTo("evt.of.payee.verification-completed.v1");
        assertThat(row.getEventType()).isEqualTo("OpenFinance.PayeeVerification.VerificationCompleted.v1");
        assertThat(row.getAggregateType()).isEqualTo("PayeeVerification");
        assertThat(row.getAggregateId()).isEqualTo(ID.toString());
        assertThat(row.getAggregateVersion()).isZero();
        assertThat(row.getCorrelationId()).isEqualTo("ix-1");
        assertThat(row.getOccurredAt()).isEqualTo(AT);

        JsonNode envelope = json.readTree(row.getPayload());
        assertThat(envelope.fieldNames()).toIterable().containsExactly("eventId", "eventType", "occurredAt",
            "aggregateId", "aggregateVersion", "correlationId", "causationId", "producer", "data");
        assertThat(envelope.get("eventId").asText()).isEqualTo(row.getEventId().toString());
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-of-payee-verification");
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-10-08T09:30:00Z");

        JsonNode data = envelope.get("data");
        assertThat(data.fieldNames()).toIterable().containsExactly("verificationId", "tppId", "accountReferenceHash",
            "outcome", "reasonCode", "occurredAt");
        assertThat(data.get("verificationId").asText()).isEqualTo(ID.toString());
        assertThat(data.get("tppId").asText()).isEqualTo("tpp-alpha");
        assertThat(data.get("accountReferenceHash").asText()).isEqualTo(HASH);
        assertThat(data.get("outcome").asText()).isEqualTo("CLOSE_MATCH");
        assertThat(data.get("reasonCode").asText()).isEqualTo("CLOSE_NAME_MATCH");
    }

    @Test
    void payloadCarriesNoRawAccountIdentification() {
        OutboxEventJpaEntity row = factory.toOutboxRow(new PayeeVerificationCompleted(ID, "tpp-alpha", "ix-1", HASH,
            MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, AT));

        assertThat(row.getPayload()).doesNotContain("AE280330000000123456789");
    }

    @Test
    void serialisationFailureIsReported() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null));

        assertThatThrownBy(() -> new PayeeVerificationEventEnvelopeFactory(broken).toOutboxRow(
            new PayeeVerificationCompleted(ID, "tpp", "ix", HASH, MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, AT)))
            .isInstanceOf(IllegalStateException.class);
    }
}
