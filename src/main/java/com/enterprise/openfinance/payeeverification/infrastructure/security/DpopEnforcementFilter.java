package com.enterprise.openfinance.payeeverification.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a DPoP-bound access token and a valid DPoP proof on the
 * TPP-facing API (platform contract, FAPI 2.0). Internal client-credentials
 * callers (realm role {@code service}) whose tokens are not DPoP-bound are
 * exempt; mesh mTLS binds them instead.
 */
public class DpopEnforcementFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/open-finance/";

    private final DpopProofValidator validator;
    private final SecurityErrorWriter errors;

    public DpopEnforcementFilter(DpopProofValidator validator, SecurityErrorWriter errors) {
        this.validator = validator;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        String boundJkt = TppIdentity.boundJkt(jwt);
        if (boundJkt == null && TppIdentity.isInternalService(jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String proof = request.getHeader("DPoP");
        if (proof == null || proof.isBlank()) {
            errors.unauthorized(request, response, "AUTH_HEADER_MISSING", "Missing required header: DPoP", "invalid_dpop_proof");
            return;
        }
        if (boundJkt == null) {
            errors.unauthorized(request, response, "INVALID_DPOP_PROOF", "Access token is not DPoP-bound (cnf.jkt missing)", "invalid_token");
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !DpopOrBearerTokenResolver.DPOP_SCHEME.equals(DpopOrBearerTokenResolver.scheme(authorization))) {
            errors.unauthorized(request, response, "INVALID_DPOP_PROOF", "DPoP-bound token must use the DPoP authorization scheme", "invalid_token");
            return;
        }
        try {
            validator.validate(proof, request.getMethod(), request.getRequestURL().toString(), request.getRequestURI(),
                jwt.getTokenValue(), boundJkt);
        } catch (DpopValidationException e) {
            errors.unauthorized(request, response, "INVALID_DPOP_PROOF", e.getMessage(), "invalid_dpop_proof");
            return;
        }
        chain.doFilter(request, response);
    }
}
