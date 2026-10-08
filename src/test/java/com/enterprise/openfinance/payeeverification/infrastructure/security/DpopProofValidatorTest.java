package com.enterprise.openfinance.payeeverification.infrastructure.security;

import com.enterprise.openfinance.payeeverification.support.DpopTestSupport;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpopProofValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private static final String URL = "https://api.example.com/open-finance/v1/confirmation-of-payee/confirmation";
    private static final String TOKEN = "access-token-value";

    private final DpopTestSupport dpop = new DpopTestSupport();
    private final MemoryReplayStore replay = new MemoryReplayStore();
    private final DpopProofValidator validator = new DpopProofValidator(replay, Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(60), Duration.ofSeconds(5), "");

    @Test
    void acceptsAFreshProofBoundToTheToken() {
        String proof = dpop.proof("POST", URL, TOKEN, NOW.minusSeconds(2));

        assertThatCode(() -> validator.validate(proof, "POST", URL, "/open-finance/v1/confirmation-of-payee/confirmation",
            TOKEN, dpop.thumbprint())).doesNotThrowAnyException();
    }

    @Test
    void htuComparisonIgnoresQueryDefaultPortAndHostCase() {
        String proof = dpop.proof("POST", "https://API.example.com:443/open-finance/v1/x", TOKEN, NOW);

        assertThatCode(() -> validator.validate(proof, "post", "https://api.example.com/open-finance/v1/x?a=1", "/open-finance/v1/x",
            TOKEN, dpop.thumbprint())).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheConfiguredPublicBaseUrlBehindAGateway() {
        DpopProofValidator behindGateway = new DpopProofValidator(replay, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(60), Duration.ofSeconds(5), "https://api.example.com");
        String proof = dpop.proof("POST", URL, TOKEN, NOW);

        assertThatCode(() -> behindGateway.validate(proof, "POST",
            "http://payee-verification-service.open-finance.svc.cluster.local:8080/open-finance/v1/confirmation-of-payee/confirmation",
            "/open-finance/v1/confirmation-of-payee/confirmation", TOKEN, dpop.thumbprint())).doesNotThrowAnyException();
    }

    @Test
    void rejectsAReplayedProof() {
        String proof = dpop.proof("POST", URL, TOKEN, NOW);
        validator.validate(proof, "POST", URL, "/p", TOKEN, dpop.thumbprint());

        assertRejected(proof, "POST", URL, TOKEN, dpop.thumbprint(), "already used");
    }

    @Test
    void rejectsWrongMethodUrlTokenOrKey() {
        assertRejected(dpop.proof("GET", URL, TOKEN, NOW), "POST", URL, TOKEN, dpop.thumbprint(), "htm");
        assertRejected(dpop.proof("POST", "https://evil.example.com/x", TOKEN, NOW), "POST", URL, TOKEN, dpop.thumbprint(), "htu");
        assertRejected(dpop.proof("POST", URL, "another-token", NOW), "POST", URL, TOKEN, dpop.thumbprint(), "ath");
        assertRejected(dpop.proof("POST", URL, TOKEN, NOW), "POST", URL, TOKEN, "other-thumbprint", "cnf.jkt");
        assertRejected(dpop.proof("POST", URL, TOKEN, NOW), "POST", URL, TOKEN, null, "cnf.jkt");
    }

    @Test
    void rejectsProofsOutsideTheTimeWindow() {
        assertRejected(dpop.proof("POST", URL, TOKEN, NOW.minusSeconds(61)), "POST", URL, TOKEN, dpop.thumbprint(), "iat");
        assertRejected(dpop.proof("POST", URL, TOKEN, NOW.plusSeconds(6)), "POST", URL, TOKEN, dpop.thumbprint(), "iat");
    }

    @Test
    void rejectsWrongTypeAndMalformedProofs() {
        assertRejected(dpop.proof("POST", URL, TOKEN, NOW, "jti-1", JOSEObjectType.JWT), "POST", URL, TOKEN, dpop.thumbprint(), "typ");
        assertRejected("not-a-jwt", "POST", URL, TOKEN, dpop.thumbprint(), "signed JWT");
        assertRejected(dpop.proof("POST", "relative/path", TOKEN, NOW), "POST", URL, TOKEN, dpop.thumbprint(), "absolute");
    }

    @Test
    void rejectsASymmetricAlgorithmAndAMissingKey() throws Exception {
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("dpop+jwt"))
            .jwk(dpop.key().toPublicJWK()).build(), claims(NOW, "jti-h"));
        hmac.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
        assertRejected(hmac.serialize(), "POST", URL, TOKEN, dpop.thumbprint(), "asymmetric");

        SignedJWT noKey = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt")).build(),
            claims(NOW, "jti-k"));
        noKey.sign(new ECDSASigner(dpop.key()));
        assertRejected(noKey.serialize(), "POST", URL, TOKEN, dpop.thumbprint(), "public jwk");
    }

    @Test
    void rejectsASignatureByAnotherKeyAndAcceptsRsa() throws Exception {
        DpopTestSupport other = new DpopTestSupport();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt"))
            .jwk(dpop.key().toPublicJWK()).build(), claims(NOW, "jti-f"));
        forged.sign(new ECDSASigner(other.key()));
        assertRejected(forged.serialize(), "POST", URL, TOKEN, dpop.thumbprint(), "signature");

        RSAKey rsa = new RSAKeyGenerator(2048).generate();
        SignedJWT rsaProof = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.PS256).type(new JOSEObjectType("dpop+jwt"))
            .jwk(rsa.toPublicJWK()).build(), claims(NOW, "jti-r"));
        rsaProof.sign(new RSASSASigner(rsa));
        String rsaJkt = rsa.toPublicJWK().computeThumbprint("SHA-256").toString();
        assertThatCode(() -> validator.validate(rsaProof.serialize(), "POST", URL, "/p", TOKEN, rsaJkt)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingClaims() throws Exception {
        SignedJWT noJti = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt"))
            .jwk(dpop.key().toPublicJWK()).build(), new JWTClaimsSet.Builder()
            .claim("htm", "POST").claim("htu", URL).claim("ath", DpopTestSupport.ath(TOKEN)).issueTime(Date.from(NOW)).build());
        noJti.sign(new ECDSASigner(dpop.key()));
        assertRejected(noJti.serialize(), "POST", URL, TOKEN, dpop.thumbprint(), "jti");

        SignedJWT noIat = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt"))
            .jwk(dpop.key().toPublicJWK()).build(), new JWTClaimsSet.Builder()
            .claim("htm", "POST").claim("htu", URL).claim("ath", DpopTestSupport.ath(TOKEN)).jwtID("j").build());
        noIat.sign(new ECDSASigner(dpop.key()));
        assertRejected(noIat.serialize(), "POST", URL, TOKEN, dpop.thumbprint(), "iat");
    }

    @Test
    void athIsTheBase64UrlSha256OfTheToken() {
        // RFC 9449 section 7.1 example token and ath
        assertThat(DpopProofValidator.sha256Base64Url("Kz~8mXK1EalYznwH-LC-1fBAo.4Ljp~zsPE_NeO.gxU"))
            .isEqualTo("fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo");
    }

    private void assertRejected(String proof, String method, String url, String token, String jkt, String reason) {
        assertThatThrownBy(() -> validator.validate(proof, method, url, "/open-finance/v1/confirmation-of-payee/confirmation", token, jkt))
            .isInstanceOf(DpopValidationException.class)
            .hasMessageContaining(reason);
    }

    private static JWTClaimsSet claims(Instant iat, String jti) {
        return new JWTClaimsSet.Builder()
            .claim("htm", "POST").claim("htu", URL).claim("ath", DpopTestSupport.ath(TOKEN))
            .jwtID(jti).issueTime(Date.from(iat)).build();
    }

    static final class MemoryReplayStore implements DpopReplayStore {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean markUsed(String proofKey, Instant expiresAt) {
            return seen.add(proofKey);
        }

        @Override
        public int purgeExpired(Instant now) {
            int size = seen.size();
            seen.clear();
            return size;
        }
    }
}
