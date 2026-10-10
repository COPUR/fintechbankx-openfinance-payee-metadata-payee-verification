package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTlsUrlPolicyTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String GOOD = "jdbc:postgresql://aurora:5432/db_of_payee_verification_dev"
            + "?sslmode=verify-full&sslrootcert=" + BUNDLE;

    @Test
    void acceptsVerifiedTlsAgainstTheMountedBundle() {
        assertThatCode(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url", GOOD, BUNDLE))
                .doesNotThrowAnyException();
        assertThatCode(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url",
                GOOD + "&ApplicationName=payee&connectTimeout=10", BUNDLE)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // (a) a later sslmode that weakens the first
            "trailing sslmode=disable           | &sslmode=disable                                 | sslmode=disable is not verify-full",
            "trailing sslmode=require           | &sslmode=require                                 | sslmode=require is not verify-full",
            // (b) a factory that skips verification, and the other verification or redirection keys
            "sslfactory                         | &sslfactory=org.postgresql.ssl.NonValidatingFactory | sslfactory is refused",
            "sslfactoryarg                      | &sslfactoryarg=x                                 | sslfactoryarg is refused",
            "sslhostnameverifier                | &sslhostnameverifier=x                           | sslhostnameverifier is refused",
            "sslpasswordcallback                | &sslpasswordcallback=x                           | sslpasswordcallback is refused",
            "service                            | &service=other                                   | service is refused",
            // (c) a second sslrootcert, even an equal one
            "second sslrootcert                 | &sslrootcert=/tmp/global-bundle.pem              | sslrootcert is not the mounted RDS CA bundle",
            "repeated sslrootcert               | &sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem | sslrootcert must appear exactly once",
            "repeated sslmode                   | &sslmode=verify-full                             | sslmode must appear exactly once",
            // percent-encoded or upper-case TLS keys
            "percent-encoded sslmode key        | &%73slmode=disable                               | must not be percent-encoded",
            "upper-case SSLMODE                 | &SSLMODE=disable                                 | lower case only",
            "parameter without value            | &sslmode                                         | has no value",
            "fragment                           | #x                                               | exactly one '?'",
    })
    void refusesWeakerOrAmbiguousTlsAfterTheVerifiedParameters(String name, String suffix, String reason) {
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url", GOOD + suffix, BUNDLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("spring.datasource.url must be jdbc:postgresql://")
                .hasMessageContaining(reason);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no TLS at all          | jdbc:postgresql://aurora:5432/x                                                    | exactly one '?'",
            "no sslrootcert         | jdbc:postgresql://aurora:5432/x?sslmode=verify-full                                | sslrootcert must appear exactly once",
            "no sslmode             | jdbc:postgresql://aurora:5432/x?sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem | sslmode must appear exactly once",
            "verify-ca              | jdbc:postgresql://aurora:5432/x?sslmode=verify-ca&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem | sslmode=verify-ca is not verify-full",
            "TLS key before the '?' | jdbc:postgresql://aurora:5432/x&sslmode=disable?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem | before the '?'",
            "second '?'             | jdbc:postgresql://aurora:5432/x?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem?sslmode=disable | exactly one '?'",
            "not PostgreSQL         | jdbc:h2:mem:x?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem | not a jdbc:postgresql:// URL",
    })
    void refusesUrlsWithoutExactlyTheVerifiedParameters(String name, String url, String reason) {
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url", url, BUNDLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void refusesAMissingUrlAndNeverEchoesTheUrl() {
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url", null, BUNDLE))
                .hasMessageContaining("not a jdbc:postgresql:// URL");
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url",
                GOOD + "&password=s3cr3t-value&sslmode=disable", BUNDLE))
                .message().doesNotContain("s3cr3t-value");
    }

    @Test
    void refusesTlsDriverPropertiesOutsideTheUrl() {
        assertThatCode(() -> JdbcTlsUrlPolicy.requireNoTlsDriverProperties("spring.datasource.hikari.data-source-properties",
                Map.of("readOnlyMode", "always"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireNoTlsDriverProperties("spring.datasource.hikari.data-source-properties",
                Map.of("sslfactory", "org.postgresql.ssl.NonValidatingFactory")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data-source-properties.sslfactory is refused");
        assertThatThrownBy(() -> JdbcTlsUrlPolicy.requireNoTlsDriverProperties("spring.datasource.hikari.data-source-properties",
                Map.of("SslMode", "disable"))).isInstanceOf(IllegalStateException.class);
    }
}
