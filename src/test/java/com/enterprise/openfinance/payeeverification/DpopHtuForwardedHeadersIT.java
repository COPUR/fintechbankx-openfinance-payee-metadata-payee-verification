package com.enterprise.openfinance.payeeverification;

import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service with its real web configuration: RFC 9449 htu is checked against
 * the configured public base URL plus the request path. Host and X-Forwarded-*
 * headers are client-controlled and must not change the URL a proof is accepted for.
 * Skipped without TEST_DB_URL.
 */
@SpringBootTest(properties = {
    "openfinance.outbox.relay.enabled=false",
    "openfinance.payee-verification.directory-seed.enabled=true",
    "management.tracing.enabled=false",
    "openfinance.dpop.public-base-url=https://api.test.example"
})
@AutoConfigureMockMvc
class DpopHtuForwardedHeadersIT {

    private static final String PATH = "/open-finance/v1/confirmation-of-payee/confirmation";
    private static final String BODY = """
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
    @MockBean JwtDecoder jwtDecoder;
    @MockBean KafkaTemplate<String, String> kafka;

    private final DpopTestSupport dpop = new DpopTestSupport();

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("tpp-token")).thenReturn(dpop.tppToken("tpp-token", "tpp-alpha", Instant.now()));
    }

    @Test
    void shouldRejectProofForForwardedHostSuppliedByClient() throws Exception {
        mvc.perform(withProof("http://evil.example" + PATH)
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
    }

    @Test
    void shouldAcceptProofForConfiguredBaseUrlWhateverForwardedHostSays() throws Exception {
        mvc.perform(withProof("https://api.test.example" + PATH)
                .header("X-Forwarded-Host", "other.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "8443"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldRejectProofForTheUrlThePodSeesWhenABaseUrlIsConfigured() throws Exception {
        mvc.perform(withProof("http://localhost" + PATH))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldCheckTheProofWhenThePathPrefixIsPercentEncoded() throws Exception {
        // /open-financ%65/... reaches the controller, so it must not escape the DPoP filter:
        // a proof for another host is refused there as it is on the plain path.
        mvc.perform(withProof(URI.create("/open-financ%65/v1/confirmation-of-payee/confirmation"),
                "https://evil.example" + PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
    }

    private MockHttpServletRequestBuilder withProof(String htu) {
        return withProof(URI.create(PATH), htu);
    }

    private MockHttpServletRequestBuilder withProof(URI path, String htu) {
        return post(path).contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "DPoP tpp-token")
            .header("DPoP", dpop.proof("POST", htu, "tpp-token", Instant.now()))
            .header("X-FAPI-Interaction-ID", UUID.randomUUID().toString())
            .content(BODY);
    }
}
