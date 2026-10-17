package com.enterprise.openfinance.payeeverification.infrastructure.config;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds the real KafkaProducer from application.yml (plus a runtime profile).
 * No broker is needed: the constructor validates the configuration, e.g.
 * delivery.timeout.ms >= linger.ms + request.timeout.ms.
 */
class KafkaProducerConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "kafka-msk"})
    void producerConfigurationIsAcceptedByTheKafkaClient(String profile) throws IOException {
        Map<String, Object> config = producerConfig(profile);

        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
            .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "svc-of-payee-verification")
            .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000")
            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000")
            .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "5");
        if (!profile.isEmpty()) {
            assertThat(config).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "AWS_MSK_IAM");
        }

        try (Producer<Object, Object> producer = new DefaultKafkaProducerFactory<>(config).createProducer()) {
            assertThat(producer).isNotNull();
        }
    }

    private static Map<String, Object> producerConfig(String profile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (!profile.isEmpty()) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml")).forEach(sources::addLast);
        }
        List<PropertySource<?>> base = loader.load("application", new ClassPathResource("application.yml"));
        base.forEach(sources::addLast);
        KafkaProperties kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class)
            .orElseThrow(() -> new IllegalStateException("spring.kafka is not configured"));
        return kafka.buildProducerProperties(null);
    }
}
