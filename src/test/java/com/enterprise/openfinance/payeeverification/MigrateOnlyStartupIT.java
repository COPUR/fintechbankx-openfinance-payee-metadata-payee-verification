package com.enterprise.openfinance.payeeverification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Helm "migrate" init container runs the image with
 * --spring.main.web-application-type=none: Flyway migrates as the schema owner and
 * the process exits. The context must therefore start without a web server
 * (no HttpSecurity) and without Kafka (relay off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "openfinance.outbox.relay.enabled=false",
    "management.tracing.enabled=false"
})
class MigrateOnlyStartupIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired Flyway flyway;

    @Test
    void startsWithoutAWebServerAndLeavesTheSchemaCurrent() {
        assertThat(flyway.info().pending()).isEmpty();
    }
}
