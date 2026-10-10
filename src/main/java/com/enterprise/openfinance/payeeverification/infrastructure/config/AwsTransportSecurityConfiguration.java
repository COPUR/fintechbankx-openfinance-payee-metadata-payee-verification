package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Map;

/**
 * Under the aws profile (the chart renders SPRING_PROFILES_ACTIVE=aws,kafka-* for the
 * service container and aws for the migrate init container), startup
 * fails unless
 * <ul>
 *   <li>spring.datasource.url (and spring.flyway.url when set) carries exactly one
 *       sslmode=verify-full and exactly one sslrootcert equal to
 *       openfinance.transport-security.database-ca-bundle (JdbcTlsUrlPolicy), and no
 *       Hikari data-source property touches TLS;</li>
 *   <li>whenever Kafka is in use (a kafka-msk or kafka-strimzi profile is active, or the
 *       outbox relay is enabled), the Kafka producer and admin clients use
 *       security.protocol SASL_SSL (MSK IAM), or SSL with the kafka-strimzi profile
 *       (mutual TLS with the KafkaUser certificate). The migrate init container runs
 *       with the aws profile alone and the relay off: it never connects to Kafka, so
 *       only its database connection is checked.</li>
 * </ul>
 * It runs as a BeanFactoryPostProcessor, before the DataSource, Flyway or a Kafka client
 * exists, so nothing connects with weaker settings first. Tests and local runs do not
 * activate the aws profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile(AwsTransportSecurityConfiguration.AWS_PROFILE)
public class AwsTransportSecurityConfiguration {

    static final String AWS_PROFILE = "aws";
    static final String CA_BUNDLE_PROPERTY = "openfinance.transport-security.database-ca-bundle";
    static final String DEFAULT_CA_BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";

    @Bean
    static BeanFactoryPostProcessor awsTransportSecurityAssertion(Environment environment) {
        return beanFactory -> check(environment);
    }

    static void check(Environment environment) {
        Binder binder = Binder.get(environment);
        String caBundle = binder.bind(CA_BUNDLE_PROPERTY, String.class).orElse(DEFAULT_CA_BUNDLE);
        JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url",
                binder.bind("spring.datasource.url", String.class).orElse(null), caBundle);
        binder.bind("spring.flyway.url", String.class)
                .ifBound(url -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.flyway.url", url, caBundle));
        JdbcTlsUrlPolicy.requireNoTlsDriverProperties("spring.datasource.hikari.data-source-properties",
                binder.bind("spring.datasource.hikari.data-source-properties",
                        Bindable.mapOf(String.class, String.class)).orElse(Map.of()));

        boolean kafkaInUse = environment.acceptsProfiles(Profiles.of("kafka-msk | kafka-strimzi"))
                || binder.bind("openfinance.outbox.relay.enabled", Boolean.class).orElse(false);
        if (!kafkaInUse) {
            return;
        }
        KafkaProperties kafka = binder.bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
        String expected = environment.acceptsProfiles(Profiles.of("kafka-strimzi")) ? "SSL" : "SASL_SSL";
        requireProtocol("producer", kafka.buildProducerProperties(null), expected);
        requireProtocol("admin", kafka.buildAdminProperties(null), expected);
    }

    private static void requireProtocol(String client, Map<String, Object> properties, String expected) {
        Object protocol = properties.get("security.protocol");
        if (!expected.equals(protocol == null ? null : protocol.toString())) {
            throw new IllegalStateException("Kafka " + client + " security.protocol must be " + expected
                    + " under the aws profile, not " + protocol
                    + " (spring.kafka.security.protocol, from the kafka-msk or kafka-strimzi profile)");
        }
    }
}
