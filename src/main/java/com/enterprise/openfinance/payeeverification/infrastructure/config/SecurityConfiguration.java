package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopEnforcementFilter;
import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopOrBearerTokenResolver;
import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopProofValidator;
import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopReplayStore;
import com.enterprise.openfinance.payeeverification.infrastructure.security.SecurityErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;

/**
 * Stateless OAuth2 resource server for the platform Keycloak realm. Tokens
 * must come from the configured issuer and name this service in aud; the
 * TPP-facing API additionally requires DPoP (RFC 9449). Actuator endpoints
 * are served on the management port, which is not exposed outside the mesh.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityErrorWriter errors, DpopProofValidator dpopValidator)
        throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/open-finance/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(new DpopOrBearerTokenResolver())
                .authenticationEntryPoint((request, response, failure) -> errors.unauthorized(request, response,
                    "UNAUTHORIZED", "A valid access token for this service is required", "invalid_token"))
                .jwt(jwt -> { }))
            .addFilterAfter(new DpopEnforcementFilter(dpopValidator, errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    SecurityErrorWriter securityErrorWriter(ObjectMapper objectMapper) {
        return new SecurityErrorWriter(objectMapper);
    }

    @Bean
    DpopProofValidator dpopProofValidator(DpopReplayStore replayStore, Clock clock,
                                          @Value("${openfinance.dpop.proof-max-age:PT60S}") Duration maxAge,
                                          @Value("${openfinance.dpop.clock-skew:PT5S}") Duration clockSkew,
                                          @Value("${openfinance.dpop.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        return new DpopProofValidator(replayStore, clock, maxAge, clockSkew, publicBaseUrl);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${openfinance.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /** Signature is checked by the decoder; this adds exp/nbf, issuer and audience. */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
            aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }
}
