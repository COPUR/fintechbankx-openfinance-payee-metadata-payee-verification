package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    private final OAuth2TokenValidator<Jwt> validator =
        SecurityConfiguration.tokenValidator(DpopTestSupport.ISSUER, DpopTestSupport.AUDIENCE);

    @Test
    void acceptsATokenFromTheRealmForThisService() {
        assertThat(validator.validate(token(DpopTestSupport.ISSUER, List.of("account", DpopTestSupport.AUDIENCE))).hasErrors()).isFalse();
    }

    @Test
    void rejectsATokenForAnotherAudience() {
        assertThat(validator.validate(token(DpopTestSupport.ISSUER, List.of("svc-of-consent-authorization"))).hasErrors()).isTrue();
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        assertThat(validator.validate(token("https://evil.example.com/realms/fintechbankx", List.of(DpopTestSupport.AUDIENCE))).hasErrors()).isTrue();
    }

    @Test
    void rejectsAnExpiredToken() {
        Jwt expired = Jwt.withTokenValue("t").header("alg", "RS256").issuer(DpopTestSupport.ISSUER)
            .audience(List.of(DpopTestSupport.AUDIENCE))
            .issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(1800)).build();

        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }

    private static Jwt token(String issuer, List<String> audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuer(issuer).audience(audience)
            .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
