package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Startup TLS assertion. It runs under the aws profile (the chart renders
 * SPRING_PROFILES_ACTIVE=aws,kafka-* for the service container and aws for the migrate
 * init container) and whenever the chart-owned deployment marker FBX_DEPLOYED is in the
 * process environment (the chart renders it directly on both containers, never from
 * values, and refuses config.FBX_DEPLOYED), whatever the profiles. With the marker,
 * startup fails unless the aws profile is active, kafka-msk or kafka-strimzi is active
 * when the outbox relay is on, and no local or test profile is active. Then it fails unless
 * <ul>
 *   <li>spring.datasource.url, spring.datasource.hikari.jdbc-url and spring.flyway.url
 *       (when set) carry exactly one sslmode=verify-full and exactly one sslrootcert equal
 *       to openfinance.transport-security.database-ca-bundle (JdbcTlsUrlPolicy), and no
 *       Hikari data-source property touches TLS;</li>
 *   <li>whenever Kafka is in use (a kafka-msk or kafka-strimzi profile is active, or the
 *       outbox relay is enabled), the Kafka producer and admin clients use
 *       security.protocol SASL_SSL (MSK IAM) or SSL (Strimzi mutual TLS with the KafkaUser
 *       certificate), whatever the Kafka profile; PLAINTEXT, SASL_PLAINTEXT and an unset
 *       protocol are refused, and no producer, consumer or admin client switches broker
 *       host name verification off (an empty or "none" ssl.endpoint.identification.algorithm).
 *       The migrate init container runs with the aws profile alone and the relay off: it
 *       never connects to Kafka, so only its database connection is checked.</li>
 * </ul>
 * It runs as a BeanFactoryPostProcessor, before the DataSource, Flyway or a Kafka client
 * exists, so nothing connects with weaker settings first; {@link HikariPoolTlsAssertion}
 * then checks the effective pool after binding, before it opens. Without the marker and
 * the aws profile (tests, local runs) neither does anything.
 */
@Configuration(proxyBeanMethods = false)
public class AwsTransportSecurityConfiguration {

    static final String AWS_PROFILE = "aws";
    static final String CA_BUNDLE_PROPERTY = "openfinance.transport-security.database-ca-bundle";
    static final String DEFAULT_CA_BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";

    /** Rendered by the chart on every container that runs this assertion; read from the process environment only. */
    static final String DEPLOYED_MARKER = "FBX_DEPLOYED";
    static final List<String> KAFKA_PROFILES = List.of("kafka-msk", "kafka-strimzi");
    /** Profiles for local runs and tests; never active in a deployment. */
    static final Set<String> LOCAL_PROFILES = Set.of("local", "test");
    static final String RELAY_PROPERTY = "openfinance.outbox.relay.enabled";
    static final String ENDPOINT_IDENTIFICATION = "ssl.endpoint.identification.algorithm";

    /** SASL_SSL: MSK with IAM. SSL: Strimzi mutual TLS. Anything else sends records in clear text. */
    static final Set<String> TLS_PROTOCOLS = Set.of("SASL_SSL", "SSL");

    @Bean
    static BeanFactoryPostProcessor awsTransportSecurityAssertion(Environment environment) {
        return beanFactory -> enforce(environment, System::getenv);
    }

    @Bean
    static BeanPostProcessor awsHikariPoolTlsAssertion(Environment environment) {
        return new HikariPoolTlsAssertion(environment, System::getenv);
    }

    /**
     * With the deployment marker in {@code processEnvironment}: requires the deployed profiles,
     * then checks. Without it: checks under the aws profile only (unchanged).
     */
    static void enforce(Environment environment, Function<String, String> processEnvironment) {
        if (isDeployed(processEnvironment)) {
            requireDeployedProfiles(environment);
        }
        if (enforced(environment, processEnvironment)) {
            check(environment);
        }
    }

    /** Any value counts: the chart renders "true", and values can neither set nor clear it. */
    static boolean isDeployed(Function<String, String> processEnvironment) {
        return processEnvironment.apply(DEPLOYED_MARKER) != null;
    }

    static boolean enforced(Environment environment, Function<String, String> processEnvironment) {
        return isDeployed(processEnvironment) || environment.acceptsProfiles(Profiles.of(AWS_PROFILE));
    }

    static String caBundle(Environment environment) {
        return Binder.get(environment).bind(CA_BUNDLE_PROPERTY, String.class).orElse(DEFAULT_CA_BUNDLE);
    }

    static void requireDeployedProfiles(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        String hint = DEPLOYED_MARKER + " is set (chart-rendered deployment), so the active profiles must include "
                + AWS_PROFILE + ", one of " + KAFKA_PROFILES + " when the outbox relay is on, and no local profile "
                + LOCAL_PROFILES + "; active: " + active;
        if (!active.contains(AWS_PROFILE)) {
            throw new IllegalStateException(hint + " (the " + AWS_PROFILE + " profile is missing)");
        }
        boolean relay = Binder.get(environment).bind(RELAY_PROPERTY, Boolean.class).orElse(false);
        if (relay && active.stream().noneMatch(KAFKA_PROFILES::contains)) {
            throw new IllegalStateException(hint + " (the outbox relay is on without a kafka-msk or kafka-strimzi profile)");
        }
        active.stream().filter(profile -> LOCAL_PROFILES.contains(profile.toLowerCase(Locale.ROOT))).findFirst()
                .ifPresent(profile -> {
                    throw new IllegalStateException(hint + " (local profile " + profile + " is active)");
                });
    }

    static void check(Environment environment) {
        Binder binder = Binder.get(environment);
        String caBundle = caBundle(environment);
        JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.url",
                binder.bind("spring.datasource.url", String.class).orElse(null), caBundle);
        // Bound onto the HikariDataSource after spring.datasource.url, so it would replace the checked URL.
        binder.bind("spring.datasource.hikari.jdbc-url", String.class)
                .ifBound(url -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.datasource.hikari.jdbc-url", url, caBundle));
        binder.bind("spring.flyway.url", String.class)
                .ifBound(url -> JdbcTlsUrlPolicy.requireVerifiedTls("spring.flyway.url", url, caBundle));
        JdbcTlsUrlPolicy.requireNoTlsDriverProperties("spring.datasource.hikari.data-source-properties",
                binder.bind("spring.datasource.hikari.data-source-properties",
                        Bindable.mapOf(String.class, String.class)).orElse(Map.of()));

        boolean kafkaInUse = environment.acceptsProfiles(Profiles.of("kafka-msk | kafka-strimzi"))
                || binder.bind(RELAY_PROPERTY, Boolean.class).orElse(false);
        if (!kafkaInUse) {
            return;
        }
        KafkaProperties kafka = binder.bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
        requireProtocol("producer", kafka.buildProducerProperties(null));
        requireProtocol("admin", kafka.buildAdminProperties(null));
        requireHostNameVerification("producer", kafka.buildProducerProperties(null));
        requireHostNameVerification("consumer", kafka.buildConsumerProperties(null));
        requireHostNameVerification("admin", kafka.buildAdminProperties(null));
    }

    /** An empty (or "none") ssl.endpoint.identification.algorithm turns broker host name verification off. */
    private static void requireHostNameVerification(String client, Map<String, Object> properties) {
        if (!properties.containsKey(ENDPOINT_IDENTIFICATION)) {
            return; // the client default, https, verifies the broker host name
        }
        Object algorithm = properties.get(ENDPOINT_IDENTIFICATION);
        String value = algorithm == null ? "" : algorithm.toString().trim();
        if (value.isEmpty() || value.equalsIgnoreCase("none")) {
            throw new IllegalStateException("Kafka " + client + " " + ENDPOINT_IDENTIFICATION
                    + " must not be empty or none: broker host name verification stays on (default https)");
        }
    }

    private static void requireProtocol(String client, Map<String, Object> properties) {
        Object protocol = properties.get("security.protocol");
        if (protocol == null || !TLS_PROTOCOLS.contains(protocol.toString())) {
            throw new IllegalStateException("Kafka " + client + " security.protocol must be SASL_SSL or SSL"
                    + " under the aws profile, not " + protocol
                    + " (spring.kafka.security.protocol, from the kafka-msk or kafka-strimzi profile)");
        }
    }
}
