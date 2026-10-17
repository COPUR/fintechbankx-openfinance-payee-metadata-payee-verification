package com.enterprise.openfinance.payeeverification.infrastructure.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Map;

/** Caller identity taken from the validated access token, never from the request body. */
public final class TppIdentity {

    static final String SERVICE_ROLE = "service";

    private TppIdentity() {
    }

    /** The OAuth client id of the caller: Keycloak azp, else client_id, else sub. */
    public static String clientId(Jwt jwt) {
        for (String claim : new String[] {"azp", "client_id", "sub"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Access token identifies no client");
    }

    /** cnf.jkt of a DPoP-bound token, or null. */
    public static String boundJkt(Jwt jwt) {
        Object cnf = jwt.getClaims().get("cnf");
        if (cnf instanceof Map<?, ?> confirmation && confirmation.get("jkt") instanceof String jkt && !jkt.isBlank()) {
            return jkt;
        }
        return null;
    }

    /** Internal client-credentials caller (realm role {@code service}). */
    public static boolean isInternalService(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        return realmAccess instanceof Map<?, ?> access
            && access.get("roles") instanceof Collection<?> roles
            && roles.contains(SERVICE_ROLE);
    }
}
