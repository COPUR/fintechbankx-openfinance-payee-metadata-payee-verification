package com.enterprise.openfinance.payeeverification.infrastructure.outbox;

import com.enterprise.openfinance.payeeverification.domain.event.PayeeVerificationCompleted;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider AsyncAPI is the single source the catalog mirrors: it must match
 * the topic catalog entry and what the code actually publishes.
 */
@SuppressWarnings("unchecked")
class AsyncApiContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("api/asyncapi/svc-of-payee-verification.yaml"))) {
            spec = new Yaml().load(in);
        }
    }

    @Test
    void onlyTheProvidersOwnTopicIsDeclaredNoDeadLetterChannel() {
        // DLQs are consumer-owned (ADR-019, ADR-024): the provider declares none.
        assertThat(map(spec, "channels")).containsOnlyKeys("verificationCompleted");
        assertThat(map(map(spec, "components"), "messages")).containsOnlyKeys("PayeeVerificationCompleted");
    }

    @Test
    void topicMatchesTheCatalogEntry() {
        Map<String, Object> channel = map(map(spec, "channels"), "verificationCompleted");
        Map<String, Object> kafka = map(map(channel, "bindings"), "kafka");
        Map<String, Object> config = map(kafka, "topicConfiguration");

        assertThat(channel.get("address")).isEqualTo(PayeeVerificationEventEnvelopeFactory.TOPIC);
        assertThat(kafka.get("topic")).isEqualTo(PayeeVerificationEventEnvelopeFactory.TOPIC);
        assertThat(kafka.get("partitions")).isEqualTo(6);
        assertThat(kafka.get("replicas")).isEqualTo(3);
        assertThat(config.get("cleanup.policy")).isEqualTo(List.of("delete"));
        assertThat(((Number) config.get("retention.ms")).longValue()).isEqualTo(7L * 24 * 60 * 60 * 1000);
    }

    @Test
    void enumsAreTheCodesEnums() {
        Map<String, Object> props = map(data(), "properties");

        assertThat((List<String>) map(props, "outcome").get("enum"))
            .containsExactlyElementsOf(names(MatchOutcome.values()));
        assertThat((List<String>) map(props, "reasonCode").get("enum"))
            .containsExactlyElementsOf(names(VerificationReasonCode.values()));
    }

    @Test
    void payloadIsClosedAndListsExactlyWhatTheCodePublishes() throws IOException {
        OutboxEventJpaEntity row = new PayeeVerificationEventEnvelopeFactory(new ObjectMapper())
            .toOutboxRow(new PayeeVerificationCompleted(UUID.randomUUID(), "tpp-alpha", "ix-1", "a".repeat(64),
                MatchOutcome.MATCH, VerificationReasonCode.EXACT_NAME_MATCH, Instant.parse("2026-10-08T09:30:00Z")));
        JsonNode published = new ObjectMapper().readTree(row.getPayload());
        List<String> envelopeFields = new ArrayList<>();
        published.fieldNames().forEachRemaining(envelopeFields::add);
        List<String> dataFields = new ArrayList<>();
        published.get("data").fieldNames().forEachRemaining(dataFields::add);

        assertThat(data().get("additionalProperties")).isEqualTo(false);
        assertThat(map(data(), "properties")).containsOnlyKeys(dataFields.toArray(String[]::new));
        assertThat((List<String>) data().get("required")).containsExactlyInAnyOrderElementsOf(dataFields);
        assertThat((List<String>) map(map(map(spec, "components"), "schemas"), "EventEnvelope").get("required"))
            .containsExactlyInAnyOrderElementsOf(envelopeFields);
    }

    private static Map<String, Object> data() {
        return map(map(map(spec, "components"), "schemas"), "PayeeVerificationCompletedData");
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
