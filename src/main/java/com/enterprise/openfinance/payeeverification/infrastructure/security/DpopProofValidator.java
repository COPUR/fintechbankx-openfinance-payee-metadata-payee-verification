package com.enterprise.openfinance.payeeverification.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Validates a DPoP proof against the request and the access token
 * (RFC 9449 section 4.3 and 7.1): typ dpop+jwt, asymmetric alg, public JWK
 * in the header, valid signature, htm/htu match the request, iat inside the
 * acceptance window, ath is the token hash, the JWK thumbprint equals the
 * token's cnf.jkt, and the jti has not been used before.
 */
public class DpopProofValidator {

    private static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");

    private final DpopReplayStore replayStore;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration clockSkew;
    private final String publicBaseUrl;

    public DpopProofValidator(DpopReplayStore replayStore, Clock clock, Duration maxAge, Duration clockSkew,
                              String publicBaseUrl) {
        this.replayStore = replayStore;
        this.clock = clock;
        this.maxAge = maxAge;
        this.clockSkew = clockSkew;
        this.publicBaseUrl = validBaseUrl(publicBaseUrl);
    }

    /**
     * @param proof        value of the DPoP header
     * @param method       HTTP method of the request
     * @param requestUri   request path; the expected htu is the configured public base URL plus this path.
     *                     Host and X-Forwarded-* headers are client-controlled and never used for htu.
     * @param accessToken  raw access token
     * @param boundJkt     cnf.jkt claim of the access token
     */
    public void validate(String proof, String method, String requestUri,
                         String accessToken, String boundJkt) {
        SignedJWT jwt = parse(proof);
        JWSHeader header = jwt.getHeader();
        if (!DPOP_TYPE.equals(header.getType())) {
            throw new DpopValidationException("DPoP proof typ must be dpop+jwt");
        }
        JWK jwk = header.getJWK();
        if (jwk == null || jwk.isPrivate()) {
            throw new DpopValidationException("DPoP proof must carry a public jwk");
        }
        verifySignature(jwt, header.getAlgorithm(), jwk);

        JWTClaimsSet claims = claims(jwt);
        if (!method.equalsIgnoreCase(stringClaim(claims, "htm"))) {
            throw new DpopValidationException("DPoP htm does not match the request method");
        }
        String htu = normalizeUrl(stringClaim(claims, "htu"));
        if (!htu.equals(normalizeUrl(publicBaseUrl + requestUri))) {
            throw new DpopValidationException("DPoP htu does not match the request URL");
        }
        Instant issuedAt = issuedAt(claims);
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(clockSkew)) || issuedAt.isBefore(now.minus(maxAge))) {
            throw new DpopValidationException("DPoP proof iat is outside the acceptance window");
        }
        if (!sha256Base64Url(accessToken).equals(stringClaim(claims, "ath"))) {
            throw new DpopValidationException("DPoP ath does not match the access token");
        }
        String jkt = thumbprint(jwk);
        if (boundJkt == null || !boundJkt.equals(jkt)) {
            throw new DpopValidationException("DPoP key does not match the token cnf.jkt");
        }
        String jti = stringClaim(claims, "jti");
        String proofKey = HexFormat.of().formatHex(sha256((jkt + ":" + jti).getBytes(StandardCharsets.UTF_8)));
        if (!replayStore.markUsed(proofKey, issuedAt.plus(maxAge).plus(clockSkew))) {
            throw new DpopValidationException("DPoP proof was already used");
        }
    }

    /** Absolute http(s) origin, optionally with a path prefix; no query or fragment. Blank is refused. */
    static String validBaseUrl(String value) {
        String base = value == null ? "" : value.strip();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("openfinance.dpop.public-base-url (DPOP_PUBLIC_BASE_URL) must be set");
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("openfinance.dpop.public-base-url is not a valid URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
            || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                "openfinance.dpop.public-base-url must be an absolute http(s) URL without query or fragment");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    static String sha256Base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(value.getBytes(StandardCharsets.US_ASCII)));
    }

    static String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url.strip());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new DpopValidationException("DPoP htu must be an absolute URL");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path;
        } catch (IllegalArgumentException e) {
            throw new DpopValidationException("DPoP htu is not a valid URL");
        }
    }

    private static SignedJWT parse(String proof) {
        try {
            return SignedJWT.parse(proof);
        } catch (ParseException e) {
            throw new DpopValidationException("DPoP proof is not a signed JWT");
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DpopValidationException("DPoP proof claims are malformed");
        }
    }

    private static void verifySignature(SignedJWT jwt, JWSAlgorithm alg, JWK jwk) {
        try {
            JWSVerifier verifier;
            if (JWSAlgorithm.Family.RSA.contains(alg) && jwk instanceof RSAKey rsa) {
                verifier = new RSASSAVerifier(rsa);
            } else if (JWSAlgorithm.Family.EC.contains(alg) && jwk instanceof ECKey ec) {
                verifier = new ECDSAVerifier(ec);
            } else {
                throw new DpopValidationException("DPoP proof must use an asymmetric RS, PS or ES algorithm");
            }
            if (!jwt.verify(verifier)) {
                throw new DpopValidationException("DPoP proof signature is invalid");
            }
        } catch (JOSEException e) {
            throw new DpopValidationException("DPoP proof signature cannot be verified");
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DpopValidationException("DPoP proof claim " + name + " is required");
        }
        return text;
    }

    private static Instant issuedAt(JWTClaimsSet claims) {
        Date iat = claims.getIssueTime();
        if (iat == null) {
            throw new DpopValidationException("DPoP proof claim iat is required");
        }
        return iat.toInstant();
    }

    private static String thumbprint(JWK jwk) {
        try {
            return jwk.computeThumbprint("SHA-256").toString();
        } catch (JOSEException e) {
            throw new DpopValidationException("DPoP jwk thumbprint cannot be computed");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static List<String> supportedAlgorithms() {
        return List.of("ES256", "ES384", "PS256", "RS256");
    }
}
