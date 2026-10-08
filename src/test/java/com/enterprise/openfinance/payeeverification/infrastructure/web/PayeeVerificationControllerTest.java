package com.enterprise.openfinance.payeeverification.infrastructure.web;

import com.enterprise.openfinance.payeeverification.domain.command.VerifyPayeeCommand;
import com.enterprise.openfinance.payeeverification.domain.exception.IdempotencyKeyConflictException;
import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;
import com.enterprise.openfinance.payeeverification.domain.model.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.model.PayeeVerification;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationReasonCode;
import com.enterprise.openfinance.payeeverification.domain.model.VerificationResult;
import com.enterprise.openfinance.payeeverification.domain.port.in.VerifyPayeeUseCase;
import com.enterprise.openfinance.payeeverification.infrastructure.config.SecurityConfiguration;
import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopReplayStore;
import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice with the real security chain: issuer/audience are covered in
 * SecurityConfigurationTest, here the decoder is mocked and DPoP proofs are real.
 */
@WebMvcTest(controllers = PayeeVerificationController.class, properties = {
    "openfinance.security.audience=svc-of-payee-verification",
    // MockMvc requests are http://localhost/...
    "openfinance.dpop.public-base-url=http://localhost"
})
@Import({SecurityConfiguration.class, PayeeVerificationControllerTest.TestBeans.class})
class PayeeVerificationControllerTest {

    private static final String PATH = "/open-finance/v1/confirmation-of-payee/confirmation";
    private static final String URL = "http://localhost" + PATH;
    private static final String BODY = """
            {"Data": {"Identification": "AE28 0330 0000 0012 3456 789", "SchemeName": "IBAN", "Name": "Al Tariq Trading LLC"}}
            """;

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired MockMvc mvc;
    @MockBean VerifyPayeeUseCase useCase;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean DpopReplayStore replayStore;

    private final DpopTestSupport dpop = new DpopTestSupport();

    @BeforeEach
    void setUp() {
        when(replayStore.markUsed(anyString(), any())).thenReturn(true);
        when(jwtDecoder.decode("tpp-token")).thenReturn(dpop.tppToken("tpp-token", "tpp-alpha", Instant.now()));
        when(jwtDecoder.decode("service-token")).thenReturn(
            DpopTestSupport.serviceToken("service-token", "svc-pay-initiation-settlement", Instant.now()));
        when(jwtDecoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));
    }

    @Test
    void closeMatchWithAValidDpopProofReturnsTheHolderNameAndTakesTheTppFromTheToken() throws Exception {
        when(useCase.verify(any())).thenReturn(result(MatchOutcome.CLOSE_MATCH, AccountStatus.ACTIVE,
            VerificationReasonCode.CLOSE_NAME_MATCH, "Al Tareq Trading LLC"));

        tppCall(BODY, "ix-100")
            .andExpect(status().isOk())
            .andExpect(header().string("X-FAPI-Interaction-ID", "ix-100"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.Data.AccountStatus").value("Active"))
            .andExpect(jsonPath("$.Data.NameMatched").value("CloseMatch"))
            .andExpect(jsonPath("$.Data.MatchedName").value("Al Tareq Trading LLC"));

        ArgumentCaptor<VerifyPayeeCommand> command = ArgumentCaptor.forClass(VerifyPayeeCommand.class);
        verify(useCase).verify(command.capture());
        assertThat(command.getValue().tppId()).isEqualTo("tpp-alpha");
        assertThat(command.getValue().interactionId()).isEqualTo("ix-100");
        assertThat(command.getValue().account()).isEqualTo(AccountReference.of("IBAN", "AE280330000000123456789"));
        assertThat(command.getValue().requestedName()).isEqualTo("Al Tariq Trading LLC");
    }

    @Test
    void exactMatchDoesNotEchoTheHolderNameAndUnknownAccountsReadAsClosed() throws Exception {
        when(useCase.verify(any())).thenReturn(
            result(MatchOutcome.MATCH, AccountStatus.ACTIVE, VerificationReasonCode.EXACT_NAME_MATCH, "Al Tareq Trading LLC"),
            result(MatchOutcome.UNABLE_TO_CHECK, AccountStatus.UNKNOWN, VerificationReasonCode.ACCOUNT_NOT_FOUND, null),
            result(MatchOutcome.UNABLE_TO_CHECK, AccountStatus.DECEASED, VerificationReasonCode.ACCOUNT_DECEASED, null),
            result(MatchOutcome.NO_MATCH, AccountStatus.ACTIVE, VerificationReasonCode.NAME_MISMATCH, null));

        tppCall(BODY, "ix-101").andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.NameMatched").value("Match"))
            .andExpect(jsonPath("$.Data.MatchedName").doesNotExist());
        tppCall(BODY, "ix-102").andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.AccountStatus").value("Closed"))
            .andExpect(jsonPath("$.Data.NameMatched").value("UnableToCheck"));
        tppCall(BODY, "ix-103").andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.AccountStatus").value("Deceased"));
        tppCall(BODY, "ix-104").andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.NameMatched").value("NoMatch"));
    }

    @Test
    void missingDpopHeaderIsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "DPoP tpp-token")
                .header("X-FAPI-Interaction-ID", "ix-1")
                .content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("DPoP")))
            .andExpect(jsonPath("$.code").value("AUTH_HEADER_MISSING"))
            .andExpect(jsonPath("$.interactionId").value("ix-1"));
        verify(useCase, never()).verify(any());
    }

    @Test
    void missingOrInvalidTokenIsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-FAPI-Interaction-ID", "ix-2").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "DPoP forged").header("DPoP", "x").header("X-FAPI-Interaction-ID", "ix-3").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(useCase, never()).verify(any());
    }

    @Test
    void replayedOrMisdirectedProofIsRejected() throws Exception {
        when(replayStore.markUsed(anyString(), any())).thenReturn(false);
        tppCall(BODY, "ix-4").andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"))
            .andExpect(jsonPath("$.message").value("DPoP proof was already used"));

        when(replayStore.markUsed(anyString(), any())).thenReturn(true);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "DPoP tpp-token")
                .header("DPoP", dpop.proof("POST", "http://localhost/open-finance/v1/other", "tpp-token", Instant.now()))
                .header("X-FAPI-Interaction-ID", "ix-5").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
        verify(useCase, never()).verify(any());
    }

    @Test
    void boundTokenSentWithTheBearerSchemeIsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer tpp-token")
                .header("DPoP", dpop.proof("POST", URL, "tpp-token", Instant.now()))
                .header("X-FAPI-Interaction-ID", "ix-6").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"));
    }

    @Test
    void tppTokenThatIsNotDpopBoundIsRejected() throws Exception {
        Jwt unbound = Jwt.withTokenValue("unbound").header("alg", "RS256").claim("azp", "tpp-alpha")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        when(jwtDecoder.decode("unbound")).thenReturn(unbound);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer unbound")
                .header("DPoP", dpop.proof("POST", URL, "unbound", Instant.now()))
                .header("X-FAPI-Interaction-ID", "ix-7").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DPOP_PROOF"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cnf.jkt")));
    }

    @Test
    void internalServiceCallerIsExemptFromDpop() throws Exception {
        when(useCase.verify(any())).thenReturn(result(MatchOutcome.MATCH, AccountStatus.ACTIVE,
            VerificationReasonCode.EXACT_NAME_MATCH, null));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer service-token")
                .header("X-FAPI-Interaction-ID", "ix-8").content(BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<VerifyPayeeCommand> command = ArgumentCaptor.forClass(VerifyPayeeCommand.class);
        verify(useCase).verify(command.capture());
        assertThat(command.getValue().tppId()).isEqualTo("svc-pay-initiation-settlement");
    }

    @Test
    void missingInteractionIdIsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "DPoP tpp-token")
                .header("DPoP", dpop.proof("POST", URL, "tpp-token", Instant.now()))
                .content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_HEADER_MISSING"))
            .andExpect(jsonPath("$.interactionId").value("N/A"));
    }

    @Test
    void invalidBodiesAreBadRequests() throws Exception {
        tppCall("""
                {"Data": {"SchemeName": "IBAN", "Name": "Al Tareq Trading LLC"}}
                """, "ix-9")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Invalid request fields: Data.Identification"));
        tppCall("{not json", "ix-10")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        tppCall("""
                {"Data": {"Identification": "AE29000000123456789", "SchemeName": "IBAN", "Name": "Al Tareq Trading LLC"}}
                """, "ix-11")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Identification is not a valid IBAN"));
        tppCall(BODY, "x".repeat(129))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(useCase, never()).verify(any());
    }

    @Test
    void reusedInteractionIdForAnotherAccountIsAConflict() throws Exception {
        when(useCase.verify(any())).thenThrow(new IdempotencyKeyConflictException("ix-12"));

        tppCall(BODY, "ix-12")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andExpect(header().string("X-FAPI-Interaction-ID", "ix-12"));
    }

    @Test
    void unexpectedFailureIsA500WithoutDetails() throws Exception {
        when(useCase.verify(any())).thenThrow(new IllegalStateException("db said: Al Tareq Trading LLC"));

        tppCall(BODY, "ix-13")
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Unexpected error occurred"));
    }

    @Test
    void aDisconnectedClientIsNotReportedAsAnError() throws Exception {
        when(useCase.verify(any())).thenAnswer(invocation -> {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone");
        });

        tppCall(BODY, "ix-15").andExpect(status().isOk());
    }

    @Test
    void unsupportedMethodIsAClientError() throws Exception {
        mvc.perform(withProof(get(PATH), "GET", "ix-14"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    @Test
    void anythingOutsideTheApiIsDenied() throws Exception {
        mvc.perform(get("/internal/debug").header("Authorization", "Bearer service-token"))
            .andExpect(status().isForbidden());
    }

    private ResultActions tppCall(String body, String interactionId) throws Exception {
        return mvc.perform(withProof(post(PATH), "POST", interactionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private MockHttpServletRequestBuilder withProof(MockHttpServletRequestBuilder request, String method, String interactionId) {
        return request
            .header("Authorization", "DPoP tpp-token")
            .header("DPoP", dpop.proof(method, URL, "tpp-token", Instant.now()))
            .header("X-FAPI-Interaction-ID", interactionId);
    }

    private static VerificationResult result(MatchOutcome outcome, AccountStatus status, VerificationReasonCode reason,
                                             String matchedName) {
        PayeeVerification verification = PayeeVerification.restore(UUID.randomUUID(), "tpp-alpha", "ix",
            AccountReference.of("IBAN", "AE280330000000123456789").hash(), status, outcome, reason,
            outcome == MatchOutcome.MATCH ? 100 : 90, Instant.now());
        return new VerificationResult(verification, matchedName, false);
    }
}
