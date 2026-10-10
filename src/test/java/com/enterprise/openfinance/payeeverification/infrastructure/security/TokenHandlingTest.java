package com.enterprise.openfinance.payeeverification.infrastructure.security;

import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenHandlingTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private final DpopOrBearerTokenResolver resolver = new DpopOrBearerTokenResolver();

    @Test
    void resolvesDpopAndBearerSchemesOnly() {
        assertThat(resolver.resolve(request("DPoP abc.def.ghi"))).isEqualTo("abc.def.ghi");
        assertThat(resolver.resolve(request("Bearer abc.def.ghi"))).isEqualTo("abc.def.ghi");
        assertThat(resolver.resolve(request("Basic dXNlcjpwYXNz"))).isNull();
        assertThat(resolver.resolve(request(null))).isNull();
        assertThatThrownBy(() -> resolver.resolve(request("DPoP   "))).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void tppIdComesFromAzpThenClientIdThenSubject() {
        DpopTestSupport dpop = new DpopTestSupport();
        assertThat(TppIdentity.clientId(dpop.tppToken("t", "tpp-alpha", NOW))).isEqualTo("tpp-alpha");
        Jwt clientIdOnly = Jwt.withTokenValue("t").header("alg", "RS256").claim("client_id", "tpp-beta").build();
        assertThat(TppIdentity.clientId(clientIdOnly)).isEqualTo("tpp-beta");
        Jwt subjectOnly = Jwt.withTokenValue("t").header("alg", "RS256").subject("tpp-gamma").build();
        assertThat(TppIdentity.clientId(subjectOnly)).isEqualTo("tpp-gamma");
        Jwt none = Jwt.withTokenValue("t").header("alg", "RS256").claim("scope", "x").build();
        assertThatThrownBy(() -> TppIdentity.clientId(none)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognisesBindingAndInternalServiceTokens() {
        DpopTestSupport dpop = new DpopTestSupport();
        Jwt tpp = dpop.tppToken("t", "tpp-alpha", NOW);
        Jwt service = DpopTestSupport.serviceToken("t", "svc-pay-initiation-settlement", NOW);

        assertThat(TppIdentity.boundJkt(tpp)).isEqualTo(dpop.thumbprint());
        assertThat(TppIdentity.isInternalService(tpp)).isFalse();
        assertThat(TppIdentity.boundJkt(service)).isNull();
        assertThat(TppIdentity.isInternalService(service)).isTrue();
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
