package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5: under the aws profile the service refuses to start unless JDBC verifies
 * Aurora's certificate against the mounted RDS CA bundle and Kafka is SASL_SSL (MSK IAM) or
 * SSL (Strimzi mutual TLS), whatever the Kafka profile (round 6); PLAINTEXT, SASL_PLAINTEXT
 * and an unset protocol are refused. Tests and local runs have no aws profile and still start.
 * Round 8: registered whatever the profiles (the deployment marker is covered in
 * AwsTransportSecurityDeploymentMarkerTest), spring.datasource.hikari.jdbc-url, the effective
 * pool, and Kafka broker host name verification.
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
    void isRegisteredWithoutTheAwsProfileButDoesNothingWithoutTheDeploymentMarker() {
        // Round 8: registered whatever the profiles, so a deployment without the aws profile is
        // still asserted (FBX_DEPLOYED); tests and local runs have neither and start unchanged.
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_of_payee_verification_local",
                        "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasBean("awsTransportSecurityAssertion").hasBean("awsHikariPoolTlsAssertion"));
    }

    @Test
    void refusesAHikariJdbcUrlThatReplacesTheCheckedUrlWithWeakerTls() {
        // spring.datasource.hikari.jdbc-url is bound onto the pool after spring.datasource.url.
        aws("spring.datasource.hikari.jdbc-url=" + GOOD + "&sslmode=require")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("spring.datasource.hikari.jdbc-url must be")
                        .hasMessageContaining("sslmode=require is not verify-full"));
        aws("spring.datasource.hikari.jdbc-url=jdbc:postgresql://elsewhere:5432/x")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("spring.datasource.hikari.jdbc-url must be"));
        aws("spring.datasource.hikari.jdbc-url=" + GOOD)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesKafkaBrokerHostNameVerificationSwitchedOff() {
        for (String scope : List.of("", "producer.", "consumer.", "admin.")) {
            for (String value : List.of("", "none", " NONE ")) {
                String property = "spring.kafka." + scope + "properties.ssl.endpoint.identification.algorithm=" + value;
                aws(property).run(context -> startupFailure(context)
                        .as(property)
                        .hasMessageContaining("ssl.endpoint.identification.algorithm must not be empty or none"));
            }
        }
        aws("spring.kafka.properties.ssl.endpoint.identification.algorithm=https")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void checksTheEffectivePoolSoADataSourceClassNameCannotBypassTheUrl() {
        // Hikari ignores jdbcUrl (sslmode included) when a dataSourceClassName is bound.
        ApplicationContextRunner withPool = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(AwsTransportSecurityConfiguration.class)
                .withPropertyValues("spring.profiles.active=aws,kafka-msk", "spring.datasource.url=" + GOOD,
                        "spring.kafka.security.protocol=SASL_SSL");
        withPool.run(context -> assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class));
        withPool.withPropertyValues("spring.datasource.hikari.data-source-class-name=org.postgresql.ds.PGSimpleDataSource",
                        "spring.datasource.hikari.data-source-properties.serverName=elsewhere")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("must connect through its verified jdbcUrl, not a dataSourceClassName"));
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
    void refusesKafkaWithoutTls() {
        aws("spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL")
                        .hasMessageContaining("not PLAINTEXT"));
        aws("spring.kafka.security.protocol=SASL_PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL")
                        .hasMessageContaining("not SASL_PLAINTEXT"));
        aws("spring.kafka.producer.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL"));
        aws("spring.kafka.producer.security.protocol=SASL_PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL"));
        aws("spring.kafka.properties.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("security.protocol must be SASL_SSL or SSL"));
        aws("spring.kafka.admin.security.protocol=PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka admin security.protocol must be SASL_SSL or SSL"));
        profiles("aws,kafka-strimzi", "spring.kafka.security.protocol=SASL_PLAINTEXT")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL")
                        .hasMessageContaining("not SASL_PLAINTEXT"));
    }

    @Test
    void refusesAnUnsetKafkaProtocolUnderEveryAssertedProfile() {
        for (String activeProfiles : List.of("aws,kafka-msk", "aws,kafka-strimzi")) {
            runner.withPropertyValues("spring.profiles.active=" + activeProfiles, "spring.datasource.url=" + GOOD)
                    .run(context -> startupFailure(context)
                            .as(activeProfiles)
                            .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL")
                            .hasMessageContaining("not null"));
        }
        // aws alone asserts Kafka too once the outbox relay is on.
        runner.withPropertyValues("spring.profiles.active=aws", "spring.datasource.url=" + GOOD,
                        "openfinance.outbox.relay.enabled=true")
                .run(context -> startupFailure(context)
                        .hasMessageContaining("Kafka producer security.protocol must be SASL_SSL or SSL")
                        .hasMessageContaining("not null"));
    }

    @Test
    void acceptsSaslSslOrMutualTlsUnderEveryAssertedProfile() {
        // Round 6 consistency rule: SASL_SSL (MSK IAM) or SSL (Strimzi mutual TLS), whatever the profile.
        for (String activeProfiles : List.of("aws,kafka-msk", "aws,kafka-strimzi")) {
            for (String protocol : List.of("SASL_SSL", "SSL")) {
                profiles(activeProfiles, "spring.kafka.security.protocol=" + protocol)
                        .run(context -> assertThat(context).as(activeProfiles + " " + protocol).hasNotFailed());
            }
        }
        // aws alone asserts Kafka too once the outbox relay is on.
        for (String protocol : List.of("SASL_SSL", "SSL")) {
            profiles("aws", "spring.kafka.security.protocol=" + protocol, "openfinance.outbox.relay.enabled=true")
                    .run(context -> assertThat(context).as("aws relay " + protocol).hasNotFailed());
        }
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
                .run(context -> startupFailure(context).hasMessageContaining("must be SASL_SSL or SSL"));
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
