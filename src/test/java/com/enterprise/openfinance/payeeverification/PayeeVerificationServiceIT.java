package com.enterprise.openfinance.payeeverification;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds
 * sc_of_payee_verification (plus the sample seed), Hibernate validates the
 * entities, and verifications go through HTTP with a real DPoP proof into the
 * decision table and the outbox.
 */
@SpringBootTest(properties = {
    "openfinance.outbox.relay.enabled=false",
    "openfinance.payee-verification.directory-seed.enabled=true",
    "management.tracing.enabled=false",
    // MockMvc requests are http://localhost/...
    "openfinance.dpop.public-base-url=http://localhost"
})
@AutoConfigureMockMvc
class PayeeVerificationServiceIT {

    private static final String SCHEMA = "sc_of_payee_verification";
    private static final String PATH = "/open-finance/v1/confirmation-of-payee/confirmation";
    private static final String URL = "http://localhost" + PATH;
    private static final String TAREQ_IBAN = "AE280330000000123456789";
    private static final String CLOSE_MATCH_BODY = """
        {"Data": {"Identification": "AE28 0330 0000 0012 3456 789", "SchemeName": "IBAN", "Name": "Al Tariq Trading LLC"}}
        """;

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired VerifyPayeeUseCase useCase;
    @Autowired PlatformTransactionManager transactionManager;
    @SpyBean SpringDataOutboxRepository outbox;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean KafkaTemplate<String, String> kafka;

    private final DpopTestSupport dpop = new DpopTestSupport();

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from " + SCHEMA + ".outbox_event");
        jdbc.update("delete from " + SCHEMA + ".payee_verification");
        jdbc.update("delete from " + SCHEMA + ".dpop_proof_replay");
        when(jwtDecoder.decode("tpp-token")).thenReturn(dpop.tppToken("tpp-token", "tpp-alpha", Instant.now()));
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwnsAndClassifiesTheHolderName() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = ? and table_name <> 'flyway_schema_history' order by table_name
            """, String.class, SCHEMA);
        assertThat(tables).containsExactly("dpop_proof_replay", "outbox_event", "payee_directory_entry", "payee_verification");

        String comment = jdbc.queryForObject(
            "select col_description('" + SCHEMA + ".payee_directory_entry'::regclass, 4)", String.class);
        assertThat(comment).startsWith("PII");
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".payee_directory_entry", Integer.class))
            .isGreaterThanOrEqualTo(5);
    }

    @Test
    void closeMatchIsRecordedWithItsOutboxEventAndNoNamesAreStored() throws Exception {
        call(CLOSE_MATCH_BODY, "it-ix-1", dpop.proof("POST", URL, "tpp-token", Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.AccountStatus").value("Active"))
            .andExpect(jsonPath("$.Data.NameMatched").value("CloseMatch"))
            .andExpect(jsonPath("$.Data.MatchedName").value("Al Tareq Trading LLC"));

        var decision = jdbc.queryForMap("select * from " + SCHEMA + ".payee_verification");
        assertThat(decision)
            .containsEntry("tpp_id", "tpp-alpha")
            .containsEntry("interaction_id", "it-ix-1")
            .containsEntry("account_reference_hash", AccountReference.of("IBAN", TAREQ_IBAN).hash())
            .containsEntry("match_outcome", "CLOSE_MATCH")
            .containsEntry("reason_code", "CLOSE_NAME_MATCH")
            .containsEntry("match_score", 95);

        String payload = jdbc.queryForObject("select payload::text from " + SCHEMA + ".outbox_event", String.class);
        JsonNode envelope = json.readTree(payload);
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(decision.get("verification_id").toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("OpenFinance.PayeeVerification.VerificationCompleted.v1");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("it-ix-1");
        assertThat(envelope.at("/data/outcome").asText()).isEqualTo("CLOSE_MATCH");

        String everythingStored = decision.values().stream().map(String::valueOf).collect(Collectors.joining("|")) + payload;
        assertThat(everythingStored).doesNotContain("Tareq", "Tariq", TAREQ_IBAN);
    }

    @Test
    void repeatedInteractionIdReturnsTheStoredDecisionWithoutASecondRecord() throws Exception {
        call(CLOSE_MATCH_BODY, "it-ix-2", dpop.proof("POST", URL, "tpp-token", Instant.now())).andExpect(status().isOk());
        call(CLOSE_MATCH_BODY, "it-ix-2", dpop.proof("POST", URL, "tpp-token", Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.NameMatched").value("CloseMatch"))
            .andExpect(jsonPath("$.Data.MatchedName").value("Al Tareq Trading LLC"));

        assertThat(count("payee_verification")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        call("""
            {"Data": {"Identification": "AE770330000000987654321", "SchemeName": "IBAN", "Name": "Atlas Services LLC"}}
            """, "it-ix-2", dpop.proof("POST", URL, "tpp-token", Instant.now()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void reusedDpopProofIsRejectedAcrossRequests() throws Exception {
        String proof = dpop.proof("POST", URL, "tpp-token", Instant.now());
        call(CLOSE_MATCH_BODY, "it-ix-3", proof).andExpect(status().isOk());

        call(CLOSE_MATCH_BODY, "it-ix-4", proof)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
        assertThat(count("payee_verification")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyRecordOneDecision() throws Exception {
        VerifyPayeeCommand command = new VerifyPayeeCommand(AccountReference.of("IBAN", TAREQ_IBAN),
            "Al Tareq Trading LLC", "tpp-alpha", "it-ix-race");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<VerificationResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> useCase.verify(command)));
            }
            Set<Object> ids = new java.util.HashSet<>();
            for (Future<VerificationResult> result : results) {
                ids.add(result.get().verification().verificationId());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("payee_verification")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void decisionIsRolledBackWhenTheOutboxWriteFails() {
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).saveAllAndFlush(anyList());
        try {
            assertThatThrownBy(() -> useCase.verify(new VerifyPayeeCommand(AccountReference.of("IBAN", TAREQ_IBAN),
                "Al Tareq Trading LLC", "tpp-alpha", "it-ix-atomic"))).isInstanceOf(IllegalStateException.class);
        } finally {
            Mockito.reset(outbox);
        }
        assertThat(count("payee_verification")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void decisionsAreInsertOnly() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("IBAN", TAREQ_IBAN), "Al Tareq Trading LLC",
            "tpp-alpha", "it-ix-update"));

        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".payee_verification set match_outcome = 'NO_MATCH'"))
            .hasMessageContaining("insert-only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesTheEventKeyedByVerificationId() {
        VerificationResult result = useCase.verify(new VerifyPayeeCommand(AccountReference.of("IBAN", TAREQ_IBAN),
            "Al Tareq Trading LLC", "tpp-alpha", "it-ix-relay"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(),
            100, Duration.ofSeconds(5), Duration.ofDays(7));

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("evt.of.payee.verification-completed.v1");
        assertThat(record.getValue().key()).isEqualTo(result.verification().verificationId().toString());
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    private ResultActions call(String body, String interactionId, String proof) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "DPoP tpp-token")
            .header("DPoP", proof)
            .header("X-FAPI-Interaction-ID", interactionId)
            .content(body));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + SCHEMA + "." + table, Integer.class);
    }
}
