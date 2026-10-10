package com.enterprise.openfinance.payeeverification.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import java.util.Locale;

/**
 * Reads the access token from {@code Authorization: DPoP <token>} (RFC 9449)
 * or {@code Authorization: Bearer <token>}. Whether the token must be
 * DPoP-bound is decided after authentication by {@link DpopEnforcementFilter}.
 */
public class DpopOrBearerTokenResolver implements BearerTokenResolver {

    static final String DPOP_SCHEME = "dpop";
    static final String BEARER_SCHEME = "bearer";

    @Override
    public String resolve(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String scheme = scheme(authorization);
        if (!DPOP_SCHEME.equals(scheme) && !BEARER_SCHEME.equals(scheme)) {
            return null;
        }
        String token = authorization.substring(scheme.length()).strip();
        if (token.isEmpty()) {
            BearerTokenError error = BearerTokenErrors.invalidToken("Access token is empty");
            throw new InvalidBearerTokenException(error.getDescription());
        }
        return token;
    }

    static String scheme(String authorization) {
        int space = authorization.indexOf(' ');
        return (space < 0 ? authorization : authorization.substring(0, space)).toLowerCase(Locale.ROOT);
    }
}
