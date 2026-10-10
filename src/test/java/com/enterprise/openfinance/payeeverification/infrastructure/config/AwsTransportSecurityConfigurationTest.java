package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5: under the aws profile the service refuses to start unless JDBC verifies
 * Aurora's certificate against the mounted RDS CA bundle and Kafka is SASL_SSL (MSK IAM),
 * or SSL with kafka-strimzi. Tests and local runs have no aws profile and still start.
 */
class AwsTransportSecurityConfigurationTest {

    private static final String GOOD = "jdbc:postgresql://aurora:5432/db_of_payee_verification_dev"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AwsTransportSecurityConfiguration.class);

    private ApplicationContextRunner aws(String... properties) {
        return profiles("aws,kafka-msk", properties);
    }

    private ApplicationContextRunner profiles(String activeProfiles, String... properties) {
        return runner.withPropertyValues("spring.profiles.active=" + activeProfiles, "spring.datasource.url=" + GOOD,
                        "spring.kafka.security.protocol=SASL_SSL")
                .withPropertyValues(properties);
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> startupFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        return assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startsUnderTheAwsProfileWithVerifiedJdbcTlsAndSaslSsl() {
        aws().run(context -> assertThat(context).hasNotFailed().hasBean("awsTransportSecurityAssertion"));
    }

    @Test
    void doesNothingWithoutTheAwsProfile() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_of_payee_verification_local",
                        "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("awsTransportSecurityAssertion"));
    }

    @Test
    void refusesADatasourceUrlWithAWeakerTrailingSslmode() {
        aws("spring.datasource.url=" + GOOD + "&sslmode=disable")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("spring.datasource.url must be")
                        .hasMessageContaining("sslmode=disable is not verify-full"));
    }

    @Test
    void refusesASecondSslrootcertAndANonValidatingFactory() {
        aws("spring.datasource.url=" + GOOD + "&sslrootcert=/tmp/global-bundle.pem")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("sslrootcert is not the mounted RDS CA bundle"));
        aws("spring.datasource.url=" + GOOD + "&sslfactory=org.postgresql.ssl.NonValidatingFactory")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("sslfactory is refused"));
    }

    @Test
    void refusesAnRdsCaBundleOtherThanTheConfiguredOne() {
        aws("openfinance.transport-security.database-ca-bundle=/etc/other/ca.pem")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("sslrootcert=/etc/other/ca.pem")
                        .hasMessageContaining("sslrootcert is not the mounted RDS CA bundle"));
    }

    @Test
    void refusesAWeakFlywayUrlAndTlsDriverProperties() {
        aws("spring.flyway.url=jdbc:postgresql://aurora:5432/x?sslmode=require")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("spring.flyway.url must be"));
        aws("spring.datasource.hikari.data-source-properties.sslfactory=org.postgresql.ssl.NonValidatingFactory")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("sslfactory is refused"));
    }

    @Test
    void refusesKafkaWithoutSaslSsl() {
        aws("spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL"));
        aws("spring.kafka.producer.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL"));
        aws("spring.kafka.properties.security.protocol=SSL")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("security.protocol must be SASL_SSL"));
        aws("spring.kafka.admin.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka admin security.protocol must be SASL_SSL"));
    }

    @Test
    void acceptsMutualTlsOnlyWithTheStrimziProfile() {
        profiles("aws,kafka-strimzi", "spring.kafka.security.protocol=SSL")
                .run(context -> assertThat(context).hasNotFailed());
        aws("spring.kafka.security.protocol=SSL")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("must be SASL_SSL"));
    }

    @Test
    void theMigrateInitContainerChecksTheDatabaseButNotAKafkaClientItNeverUses() {
        // The init container runs with SPRING_PROFILES_ACTIVE=aws and OUTBOX_RELAY_ENABLED=false.
        profiles("aws", "spring.kafka.security.protocol=PLAINTEXT", "openfinance.outbox.relay.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
        profiles("aws", "spring.kafka.security.protocol=PLAINTEXT", "openfinance.outbox.relay.enabled=false",
                "spring.datasource.url=" + GOOD + "&sslmode=disable")
                .run(context -> startupFailure(context).hasMessageContaining("sslmode=disable is not verify-full"));
        profiles("aws", "spring.kafka.security.protocol=PLAINTEXT", "openfinance.outbox.relay.enabled=true")
                .run(context -> startupFailure(context).hasMessageContaining("must be SASL_SSL"));
    }

    @Test
    void theChartProfilesWithTheShippedConfigurationStartOnlyWithAVerifiedDbUrl() {
        // application.yml + application-kafka-msk.yml, as the chart activates them.
        ApplicationContextRunner shipped = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AwsTransportSecurityConfiguration.class)
                .withPropertyValues("spring.profiles.active=aws,kafka-msk");
        shipped.withPropertyValues("DB_URL=" + GOOD)
                .run(context -> assertThat(context).hasNotFailed());
        shipped.run(context -> startupFailure(context)
                .hasMessageContaining("spring.datasource.url must be"));
    }
}
