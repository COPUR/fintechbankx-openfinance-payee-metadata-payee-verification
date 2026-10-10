package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Round 8: the effective pool after binding, before it opens (no connection is made here). */
class HikariPoolTlsAssertionTest {

    private static final String GOOD = "jdbc:postgresql://aurora:5432/db_of_payee_verification_dev"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";

    private static HikariDataSource pool(String jdbcUrl) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(jdbcUrl);
        return pool;
    }

    private static HikariPoolTlsAssertion assertion(String profiles, String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (String property : properties) {
            int equals = property.indexOf('=');
            environment.setProperty(property.substring(0, equals), property.substring(equals + 1));
        }
        environment.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
        return new HikariPoolTlsAssertion(environment);
    }

    @Test
    void acceptsAVerifiedPoolAndLeavesOtherBeansAlone() {
        try (HikariDataSource pool = pool(GOOD)) {
            assertThat(assertion("").postProcessAfterInitialization(pool, "dataSource")).isSameAs(pool);
        }
        Object other = new Object();
        assertThat(assertion("aws").postProcessAfterInitialization(other, "other")).isSameAs(other);
    }

    @Test
    void refusesAPoolUrlWithATrailingWeakerSslmode() {
        try (HikariDataSource pool = pool(GOOD + "&sslmode=require")) {
            assertThatThrownBy(() -> assertion("").postProcessAfterInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DataSource dataSource jdbcUrl must be")
                    .hasMessageContaining("sslmode=require is not verify-full");
        }
    }

    @Test
    void refusesTlsDataSourcePropertiesAndADataSourceClassName() {
        for (String key : new String[] {"sslmode", "sslfactory", "SSLROOTCERT", "service"}) {
            try (HikariDataSource pool = pool(GOOD)) {
                pool.addDataSourceProperty(key, "x");
                assertThatThrownBy(() -> assertion("aws").postProcessAfterInitialization(pool, "dataSource"))
                        .as(key).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("dataSourceProperties." + key + " is refused");
            }
        }
        try (HikariDataSource pool = pool(GOOD)) {
            pool.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
            assertThatThrownBy(() -> assertion("").postProcessAfterInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a dataSourceClassName or DataSource");
        }
    }

    @Test
    void doesNothingOnlyWhenEnforceIsFalse() {
        try (HikariDataSource pool = pool("jdbc:postgresql://localhost:5432/x")) {
            pool.addDataSourceProperty("sslmode", "disable");
            assertThat(assertion("test", "fintechbankx.tls.enforce=false").postProcessAfterInitialization(pool, "dataSource"))
                    .isSameAs(pool);
            assertThatThrownBy(() -> assertion("test").postProcessAfterInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DataSource dataSource jdbcUrl must be");
        }
    }
}
