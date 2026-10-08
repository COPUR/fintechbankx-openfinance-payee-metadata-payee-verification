package com.enterprise.openfinance.payeeverification;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * PostgreSQL for integration tests, from TEST_DB_URL / TEST_DB_USERNAME /
 * TEST_DB_PASSWORD (a service container in required-gates.yml ci/test, or a
 * local server). Without TEST_DB_URL the integration tests are skipped.
 */
final class PostgresTestDatabase {

    private PostgresTestDatabase() {
    }

    /** Call from a static @BeforeAll so the class is skipped, not failed, without a database. */
    static void assumeAvailable() {
        Assumptions.assumeTrue(url() != null, "Set TEST_DB_URL to run PostgreSQL integration tests");
    }

    static void register(DynamicPropertyRegistry registry) {
        String url = url();
        if (url == null) {
            return;
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "payee_test"));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "payee_test"));
    }

    private static String url() {
        String url = System.getenv("TEST_DB_URL");
        return url == null || url.isBlank() ? null : url;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
