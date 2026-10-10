package com.enterprise.openfinance.payeeverification;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeDirectoryEntry;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.OutboxRelay;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.JpaPayeeDirectoryAdapter;
import com.enterprise.openfinance.payeeverification.infrastructure.persistence.SpringDataPayeeDirectoryRepository;
import com.enterprise.openfinance.payeeverification.infrastructure.outbox.SpringDataOutboxRepository;
import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
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
import org.springframework.kafka.core.KafkaProducerException;
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
    // Seed rows (db/seed) live under scheme SAMPLE with SAMPLE- identifications.
    private static final String TAREQ_ID = "SAMPLE-AE280330000000123456789";
    private static final String CLOSE_MATCH_BODY = """
        {"Data": {"Identification": "sample-AE28 0330 0000 0012 3456 789", "SchemeName": "Sample", "Name": "Al Tariq Trading LLC"}}
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
    @Autowired SpringDataPayeeDirectoryRepository directoryRows;
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
        assertThat(tables).containsExactly("dpop_proof_replay", "outbox_event", "payee_directory_entry",
            "payee_directory_entry_history", "payee_verification");

        String comment = jdbc.queryForObject(
            "select col_description('" + SCHEMA + ".payee_directory_entry'::regclass, 4)", String.class);
        assertThat(comment).startsWith("PII");
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".payee_directory_entry", Integer.class))
            .isGreaterThanOrEqualTo(5);
    }

    @Test
    void directoryChangesLandInTheHistoryWithNameDigestsOnly() {
        // One connection, so the session's application_name reaches the trigger.
        JdbcTemplate writable = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            System.getenv("TEST_DB_URL"), env("TEST_DB_USERNAME", "payee_test"), env("TEST_DB_PASSWORD", "payee_test"), true));
        writable.execute("set application_name = 'it-history'");
        writable.update("insert into " + SCHEMA + ".payee_directory_entry (scheme_name, identification, holder_name, account_type,"
            + " account_status, updated_at) values ('IT', 'IT-HISTORY-1', 'History Holder LLC', 'BUSINESS', 'ACTIVE', now())"
            + " on conflict (scheme_name, identification) do update set account_status = 'ACTIVE', holder_name = 'History Holder LLC'");
        writable.update("update " + SCHEMA + ".payee_directory_entry set account_status = 'CLOSED', holder_name = 'Renamed Holder LLC'"
            + " where identification = 'IT-HISTORY-1'");

        var last = writable.queryForMap("select * from " + SCHEMA + ".payee_directory_entry_history"
            + " where identification = 'IT-HISTORY-1' and application_name = 'it-history' order by history_id desc limit 1");
        assertThat(last).containsEntry("operation", "UPDATE")
            .containsEntry("old_account_status", "ACTIVE").containsEntry("new_account_status", "CLOSED")
            .containsEntry("old_holder_name_sha256", sha256("History Holder LLC"))
            .containsEntry("new_holder_name_sha256", sha256("Renamed Holder LLC"));
        assertThat(last.values().stream().map(String::valueOf).collect(Collectors.joining("|")))
            .doesNotContain("History Holder", "Renamed Holder");
        assertThatThrownBy(() -> writable.update("delete from " + SCHEMA + ".payee_directory_entry_history"))
            .hasMessageContaining("append-only");
        writable.update("delete from " + SCHEMA + ".payee_directory_entry where identification = 'IT-HISTORY-1'");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
            .containsEntry("account_reference_hash", AccountReference.of("SAMPLE", TAREQ_ID).hash())
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
        assertThat(everythingStored).doesNotContain("Tareq", "Tariq", TAREQ_ID, "AE280330000000123456789");
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
            {"Data": {"Identification": "SAMPLE-AE770330000000987654321", "SchemeName": "SAMPLE", "Name": "Atlas Services LLC"}}
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
        VerifyPayeeCommand command = new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID),
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
            assertThatThrownBy(() -> useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID),
                "Al Tareq Trading LLC", "tpp-alpha", "it-ix-atomic"))).isInstanceOf(IllegalStateException.class);
        } finally {
            Mockito.reset(outbox);
        }
        assertThat(count("payee_verification")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void anUnpublishedOutboxRowGoesOnlyToThePayeeAggregateTopic() {
        String insert = "insert into " + SCHEMA + ".outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, "
            + "event_type, topic, payload, correlation_id, occurred_at) values (gen_random_uuid(), 'PayeeVerification', 'x', 0, "
            + "'OpenFinance.PayeeVerification.VerificationCompleted.v1', ?, '{}'::jsonb, 'ix-topic', now())";
        assertThatThrownBy(() -> jdbc.update(insert, "evt.of.payee.verification-completed.v1"))
            .hasMessageContaining("ck_outbox_aggregate_topic");
        jdbc.update(insert, "evt.of.payee.v1");
    }

    @Test
    void decisionsAreInsertOnly() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC",
            "tpp-alpha", "it-ix-update"));

        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".payee_verification set match_outcome = 'NO_MATCH'"))
            .hasMessageContaining("insert-only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesTheEventKeyedByVerificationId() {
        VerificationResult result = useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID),
            "Al Tareq Trading LLC", "tpp-alpha", "it-ix-relay"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(),
            100, Duration.ofSeconds(5), Duration.ofDays(7), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("evt.of.payee.v1");
        assertThat(record.getValue().key()).isEqualTo(result.verification().verificationId().toString());
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadErrorParksTheRowInPostgresAndTheNextRowIsSent() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-park-1"));
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-park-2"));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaProducerException(null, "send failed",
                new RecordTooLargeException("The message is 2000000 bytes"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay().relayOnce()).isEqualTo(1);

        assertThat(jdbc.queryForList("select parked_by || ':' || parked_reason from " + SCHEMA
            + ".outbox_event where parked_at is not null", String.class))
            .containsExactly("relay:RecordTooLargeException");
        assertThat(count("outbox_event where published_at is not null")).isEqualTo(1);
        assertThat(outbox.countByParkedAtIsNotNull()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();
        assertThat(outbox.oldestPendingAgeSeconds()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorizationErrorLeavesEveryRowUntouchedAndTheOldestPendingAgeGrows() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-auth"));
        jdbc.update("update " + SCHEMA + ".outbox_event set created_at = now() - interval '2 hours'");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
            new KafkaProducerException(null, "send failed", new TopicAuthorizationException("not authorized"))));

        assertThat(relay().relayOnce()).isZero();

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event where published_at is null"
            + " and parked_at is null and parked_reason is null and attempts = 0 and last_error is null", Long.class))
            .isEqualTo(1L);
        assertThat(outbox.oldestPendingAgeSeconds()).isGreaterThanOrEqualTo(7200.0);
    }

    @Test
    void anOperatorParksAPendingRowWithAReason() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-op"));
        String eventId = jdbc.queryForObject("select event_id::text from " + SCHEMA + ".outbox_event", String.class);
        JdbcTemplate operator = jdbc; // the test login stands in for the schema owner

        assertThatThrownBy(() -> operator.queryForObject(
            "select " + SCHEMA + ".park_outbox_event(?::uuid, ' ')", Object.class, eventId))
            .hasMessageContaining("a reason is required");
        operator.queryForObject("select " + SCHEMA + ".park_outbox_event(?::uuid, ?)", Object.class,
            eventId, "INC-1234 topic ACL missing, replay after fix");
        assertThatThrownBy(() -> operator.queryForObject(
            "select " + SCHEMA + ".park_outbox_event(?::uuid, 'again')", Object.class, eventId))
            .hasMessageContaining("already published or already parked");

        assertThat(jdbc.queryForObject("select parked_reason || '|' || (parked_by = session_user) from " + SCHEMA
            + ".outbox_event", String.class)).isEqualTo("INC-1234 topic ACL missing, replay after fix|true");
        // The ops login executes it without table rights: SECURITY DEFINER, search_path pinned (V9).
        assertThat(jdbc.queryForObject("select prosecdef || '|' || array_to_string(proconfig, ',') from pg_proc where oid = '"
            + SCHEMA + ".park_outbox_event(uuid, text)'::regprocedure", String.class))
            .isEqualTo("true|search_path=" + SCHEMA + ", pg_temp");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyParkIsCountedOnceRelayParksAtOnceAndOperatorParksOnTheNextRun() {
        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-count-1"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
            new KafkaProducerException(null, "send failed", new RecordTooLargeException("The message is 2000000 bytes"))));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(),
            100, Duration.ofSeconds(5), Duration.ofDays(7), meters);

        relay.relayOnce();
        assertThat(jdbc.queryForObject("select park_counted from " + SCHEMA + ".outbox_event where parked_by = 'relay'",
            Boolean.class)).as("counted when the relay parked it").isTrue();
        assertThat(meters.get("outbox.parked.events").tag("exception", "RecordTooLargeException").counter().count()).isEqualTo(1.0);

        useCase.verify(new VerifyPayeeCommand(AccountReference.of("SAMPLE", TAREQ_ID), "Al Tareq Trading LLC", "tpp-alpha", "it-ix-count-2"));
        String eventId = jdbc.queryForObject("select event_id::text from " + SCHEMA + ".outbox_event where parked_at is null",
            String.class);
        jdbc.queryForObject("select " + SCHEMA + ".park_outbox_event(?::uuid, ?)", Object.class, eventId, "INC-9 dropped");
        relay.relayOnce();
        relay.relayOnce();

        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count())
            .as("the operator park is counted once, not on every run").isEqualTo(1.0);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".outbox_event where not park_counted", Long.class))
            .isZero();
    }

    /** Port-level predicate: the directory matches on scheme AND identification, exactly, after normalisation. */
    @Test
    void directoryPortMatchesOnSchemeAndIdentificationTogether() {
        JpaPayeeDirectoryAdapter port = new JpaPayeeDirectoryAdapter(directoryRows);
        jdbc.update("insert into " + SCHEMA + ".payee_directory_entry (scheme_name, identification, holder_name,"
            + " account_type, account_status, updated_at) values ('SAMPLEOTHER', ?, 'Other Scheme Holder', 'PERSONAL',"
            + " 'CLOSED', now())", TAREQ_ID);
        try {
            assertThat(port.find(AccountReference.of("SAMPLE", TAREQ_ID)))
                .map(PayeeDirectoryEntry::holderName).contains("Al Tareq Trading LLC");
            assertThat(port.find(AccountReference.of(" sample ", "sample-ae28 0330 0000 0012 3456 789")))
                .map(PayeeDirectoryEntry::holderName).contains("Al Tareq Trading LLC");
            assertThat(port.find(AccountReference.of("SAMPLEOTHER", TAREQ_ID)))
                .map(PayeeDirectoryEntry::holderName).contains("Other Scheme Holder");
            assertThat(port.find(AccountReference.of("BBAN", TAREQ_ID))).isEmpty();
            assertThat(port.find(AccountReference.of("SAMPLE", TAREQ_ID + "0"))).isEmpty();
            assertThat(port.find(AccountReference.of("SAMPLE", TAREQ_ID.substring(0, TAREQ_ID.length() - 1)))).isEmpty();
        } finally {
            jdbc.update("delete from " + SCHEMA + ".payee_directory_entry where scheme_name = 'SAMPLEOTHER'");
        }
    }

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(),
            100, Duration.ofSeconds(5), Duration.ofDays(7), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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
