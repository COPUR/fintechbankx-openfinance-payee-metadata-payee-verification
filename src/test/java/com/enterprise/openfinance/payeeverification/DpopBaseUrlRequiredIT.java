package com.enterprise.openfinance.payeeverification;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DPoP htu is checked against DPOP_PUBLIC_BASE_URL only. The service must refuse
 * to start without it rather than fall back to a default origin; the chart refuses
 * to render without it too. A migrate-only run does not need it.
 * (The test JVM never inherits DPOP_PUBLIC_BASE_URL; see build.gradle.)
 */
class DpopBaseUrlRequiredIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @Test
    void theServiceRefusesToStartWithoutAPublicBaseUrl() {
        assertThatThrownBy(() -> start("--server.port=0", "--management.server.port=0").close())
            .hasStackTraceContaining("DPOP_PUBLIC_BASE_URL");
    }

    @Test
    void theServiceRefusesToStartWithABlankPublicBaseUrl() {
        assertThatThrownBy(() -> start("--server.port=0", "--management.server.port=0",
                "--openfinance.dpop.public-base-url=").close())
            .hasStackTraceContaining("DPOP_PUBLIC_BASE_URL");
    }

    @Test
    void aMigrateOnlyRunDoesNotNeedIt() {
        try (ConfigurableApplicationContext context = start("--spring.main.web-application-type=none")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static ConfigurableApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of(PostgresTestDatabase.properties()));
        args.addAll(List.of(
            "--openfinance.outbox.relay.enabled=false",
            "--management.tracing.enabled=false",
            "--spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example/realms/test",
            "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/realms/test/certs"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(Application.class).run(args.toArray(String[]::new));
    }
}
