package com.enterprise.openfinance.payeeverification.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds DPoP-bound access tokens and RFC 9449 proofs for tests. */
public final class DpopTestSupport {

    public static final String AUDIENCE = "svc-of-payee-verification";
    public static final String ISSUER = "https://identity.test/realms/fintechbankx";

    private final ECKey key;

    public DpopTestSupport() {
        try {
            key = new ECKeyGenerator(Curve.P_256).keyID("tpp-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public ECKey key() {
        return key;
    }

    public String thumbprint() {
        try {
            return key.toPublicJWK().computeThumbprint("SHA-256").toString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Access token of a TPP client, bound to this key. */
    public Jwt tppToken(String tokenValue, String clientId, Instant now) {
        return Jwt.withTokenValue(tokenValue)
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of(AUDIENCE))
            .subject("service-account-" + clientId)
            .claim("azp", clientId)
            .claim("cnf", Map.of("jkt", thumbprint()))
            .issuedAt(now.minusSeconds(10))
            .expiresAt(now.plusSeconds(300))
            .build();
    }

    /** Client-credentials token of an internal service: realm role service, not DPoP-bound. */
    public static Jwt serviceToken(String tokenValue, String clientId, Instant now) {
        return Jwt.withTokenValue(tokenValue)
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of(AUDIENCE))
            .subject("service-account-" + clientId)
            .claim("azp", clientId)
            .claim("realm_access", Map.of("roles", List.of("service")))
            .issuedAt(now.minusSeconds(10))
            .expiresAt(now.plusSeconds(300))
            .build();
    }

    public String proof(String method, String url, String accessToken, Instant iat) {
        return proof(method, url, accessToken, iat, UUID.randomUUID().toString(), new JOSEObjectType("dpop+jwt"));
    }

    public String proof(String method, String url, String accessToken, Instant iat, String jti, JOSEObjectType type) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(type)
                .jwk(key.toPublicJWK())
                .build();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("htm", method)
                .claim("htu", url)
                .claim("ath", ath(accessToken))
                .jwtID(jti)
                .issueTime(Date.from(iat))
                .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String ath(String accessToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
