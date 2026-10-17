package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail 4a, single form (governance, lending's TlsEnforcement): the startup TLS
 * assertion is on by default; its Kafka part keeps the Kafka-in-use gate (outbox relay on
 * or a kafka-* profile active). fintechbankx.tls.enforce is true in application.yml and only
 * explicit local and test configuration sets it false: the local profile
 * (application-local.yml) and the test resources (application.properties). No profile, no
 * deployment marker and no other setting is needed for the assertion to run; the chart
 * never sets the switch and refuses the key and the local profile.
 */
class TlsEnforcementByDefaultTest {

    private static final String VERIFIED = "spring.datasource.url=jdbc:postgresql://aurora:5432/db_of_payee_verification_dev"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String UNVERIFIED = "spring.datasource.url=jdbc:postgresql://localhost:5432/db_of_payee_verification_local";

    private final ApplicationContextRunner service = new ApplicationContextRunner()
            .withUserConfiguration(AwsTransportSecurityConfiguration.class);

    private static PropertySource<?> yaml(String name) throws Exception {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).getFirst();
    }

    private static String cause(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
    }

    @Test
    void theAssertionRunsWithNoProfileAndNoMarker() {
        service.withPropertyValues(UNVERIFIED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(cause(context.getStartupFailure())).contains("spring.datasource.url must be");
                });
        // Kafka is checked whenever it is in use: the outbox relay or a kafka-* profile.
        service.withPropertyValues(VERIFIED, "openfinance.outbox.relay.enabled=true", "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(cause(context.getStartupFailure()))
                            .contains("Kafka producer security.protocol must be SASL_SSL or SSL");
                });
        service.withPropertyValues(VERIFIED, "openfinance.outbox.relay.enabled=true", "spring.kafka.security.protocol=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed());
        // Without Kafka in use (relay off, no kafka-* profile) only the database is checked.
        service.withPropertyValues(VERIFIED, "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theEffectivePoolIsCheckedWithNoProfileAndNoMarker() {
        service.withPropertyValues(VERIFIED)
                .withBean("dataSource", HikariDataSource.class, () -> {
                    HikariDataSource pool = new HikariDataSource();
                    pool.setJdbcUrl("jdbc:postgresql://aurora:5432/x?sslmode=require");
                    return pool;
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(cause(context.getStartupFailure())).contains("DataSource dataSource jdbcUrl must be");
                });
    }

    @Test
    void enforceFalseSwitchesItOffWhateverTheProfiles() {
        for (String profiles : List.of("", "aws", "aws,kafka-msk")) {
            service.withPropertyValues("spring.profiles.active=" + profiles, "fintechbankx.tls.enforce=false",
                            UNVERIFIED, "spring.kafka.security.protocol=PLAINTEXT", "openfinance.outbox.relay.enabled=true")
                    .run(context -> assertThat(context).as(profiles).hasNotFailed());
        }
    }

    @Test
    void theServiceEnforcesTlsByDefault() throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getProperty("fintechbankx.tls.enforce")).isEqualTo(true);
    }

    @Test
    void onlyTheLocalProfileSwitchesItOff() throws Exception {
        assertThat(yaml("application-local.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo(false);
        assertThat(yaml("application-kafka-msk.yml").getProperty("fintechbankx.tls.enforce")).isNull();
        assertThat(yaml("application-kafka-strimzi.yml").getProperty("fintechbankx.tls.enforce")).isNull();
    }

    @Test
    void theLocalProfileStartsAgainstAPlainLocalDatabaseAndBroker() throws Exception {
        PropertySource<?> local = yaml("application-local.yml");
        service.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(local))
                .withPropertyValues(UNVERIFIED, "spring.kafka.security.protocol=PLAINTEXT", "openfinance.outbox.relay.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theTestResourcesSwitchItOffForTheIntegrationTests() throws Exception {
        PropertySource<?> tests = new PropertiesPropertySourceLoader()
                .load("application.properties", new ClassPathResource("application.properties")).getFirst();

        assertThat(tests.getProperty("fintechbankx.tls.enforce")).isEqualTo("false");
    }
}
