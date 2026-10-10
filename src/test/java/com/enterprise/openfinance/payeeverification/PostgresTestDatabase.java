package com.enterprise.openfinance.payeeverification;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Map;

/**
 * PostgreSQL for integration tests, from TEST_DB_URL / TEST_DB_USERNAME /
 * TEST_DB_PASSWORD (a service container in required-gates.yml ci/test, or a
 * local server). Without TEST_DB_URL the integration tests are skipped on a
 * developer machine but fail in CI (CI=true, as GitHub Actions and GitLab set
 * it, or JENKINS_URL set), so a pipeline cannot pass with them silently skipped.
 */
final class PostgresTestDatabase {

    private PostgresTestDatabase() {
    }

    enum Mode { RUN, SKIP, FAIL }

    /** Call from a static @BeforeAll. */
    static void assumeAvailable() {
        switch (mode(System.getenv())) {
            case RUN -> { }
            case FAIL -> Assertions.fail("TEST_DB_URL is not set in CI (CI=true or JENKINS_URL): "
                + "PostgreSQL integration tests must run, not be skipped. Provide TEST_DB_URL, "
                + "TEST_DB_USERNAME and TEST_DB_PASSWORD.");
            case SKIP -> Assumptions.abort("Set TEST_DB_URL to run PostgreSQL integration tests");
        }
    }

    static Mode mode(Map<String, String> env) {
        if (!blank(env.get("TEST_DB_URL"))) {
            return Mode.RUN;
        }
        boolean ci = "true".equalsIgnoreCase(env.get("CI")) || !blank(env.get("JENKINS_URL"));
        return ci ? Mode.FAIL : Mode.SKIP;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

    /** The same settings as {@link #register}, as command-line style properties. */
    static String[] properties() {
        String url = url();
        if (url == null) {
            return new String[0];
        }
        return new String[] {
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + env("TEST_DB_USERNAME", "payee_test"),
            "--spring.datasource.password=" + env("TEST_DB_PASSWORD", "payee_test")
        };
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
