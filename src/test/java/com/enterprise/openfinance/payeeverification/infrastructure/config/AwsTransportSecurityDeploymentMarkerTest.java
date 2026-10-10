package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round 8: the chart renders FBX_DEPLOYED=true on the migrate init container and the service
 * container (never from values). With it the assertion runs whatever the profiles, and startup
 * fails unless aws is active (plus a Kafka profile when the outbox relay is on) and no local or
 * test profile is. Without it behaviour is unchanged.
 */
class AwsTransportSecurityDeploymentMarkerTest {

    private static final String GOOD = "jdbc:postgresql://aurora:5432/db_of_payee_verification_dev"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final Function<String, String> DEPLOYED = name -> "FBX_DEPLOYED".equals(name) ? "true" : null;
    private static final Function<String, String> NOT_DEPLOYED = name -> null;

    private static MockEnvironment environment(String profiles, String... properties) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", GOOD)
                .withProperty("spring.kafka.security.protocol", "SASL_SSL");
        for (String property : properties) {
            int equals = property.indexOf('=');
            environment.setProperty(property.substring(0, equals), property.substring(equals + 1));
        }
        environment.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
        return environment;
    }

    @Test
    void withTheMarkerTheChartContainersStart() {
        // Service container: aws,kafka-*, relay on or off.
        for (String profiles : List.of("aws,kafka-msk", "aws,kafka-strimzi")) {
            for (String relay : List.of("true", "false")) {
                assertThatCode(() -> AwsTransportSecurityConfiguration.enforce(
                        environment(profiles, "openfinance.outbox.relay.enabled=" + relay), DEPLOYED))
                        .as(profiles + " relay " + relay).doesNotThrowAnyException();
            }
        }
        // Migrate init container: aws alone, relay off, never a Kafka client (a PLAINTEXT
        // protocol it never uses is not checked).
        assertThatCode(() -> AwsTransportSecurityConfiguration.enforce(environment("aws",
                "openfinance.outbox.relay.enabled=false", "spring.kafka.security.protocol=PLAINTEXT"), DEPLOYED))
                .doesNotThrowAnyException();
    }

    @Test
    void withTheMarkerStartupFailsWithoutTheAwsProfile() {
        for (String profiles : List.of("", "kafka-msk", "default")) {
            assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(environment(profiles), DEPLOYED))
                    .as(profiles).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FBX_DEPLOYED is set")
                    .hasMessageContaining("the aws profile is missing");
        }
    }

    @Test
    void withTheMarkerTheOutboxRelayNeedsAKafkaProfile() {
        assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(
                environment("aws", "openfinance.outbox.relay.enabled=true"), DEPLOYED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the outbox relay is on without a kafka-msk or kafka-strimzi profile");
    }

    @Test
    void withTheMarkerALocalOrTestProfileIsRefused() {
        for (String profiles : List.of("aws,kafka-msk,local", "test,aws", "aws,kafka-msk,LOCAL")) {
            assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(environment(profiles), DEPLOYED))
                    .as(profiles).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("local profile");
        }
    }

    @Test
    void withTheMarkerTheTlsChecksStillRun() {
        assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(
                environment("aws", "spring.datasource.url=" + GOOD + "&sslmode=disable"), DEPLOYED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode=disable is not verify-full");
        assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(
                environment("aws,kafka-strimzi", "spring.kafka.security.protocol=PLAINTEXT"), DEPLOYED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.protocol must be SASL_SSL or SSL");
    }

    @Test
    void anyMarkerValueEnforces() {
        assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(environment(""),
                name -> "FBX_DEPLOYED".equals(name) ? "" : null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the aws profile is missing");
    }

    @Test
    void withoutTheMarkerBehaviourIsUnchanged() {
        assertThatCode(() -> AwsTransportSecurityConfiguration.enforce(
                environment("", "spring.datasource.url=jdbc:postgresql://localhost:5432/x",
                        "spring.kafka.security.protocol=PLAINTEXT"), NOT_DEPLOYED))
                .doesNotThrowAnyException();
        assertThatCode(() -> AwsTransportSecurityConfiguration.enforce(environment("test"), NOT_DEPLOYED))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AwsTransportSecurityConfiguration.enforce(
                environment("aws", "spring.datasource.url=jdbc:postgresql://localhost:5432/x"), NOT_DEPLOYED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must be");
    }
}
