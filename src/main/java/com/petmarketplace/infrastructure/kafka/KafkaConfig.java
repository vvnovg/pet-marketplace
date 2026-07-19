package com.petmarketplace.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the animal-info request/reply Kafka integration from {@link KafkaIntegrationProperties}.
 * Uses Spring Kafka 4.0's Jackson-3 {@code JacksonJsonSerializer}/{@code JacksonJsonDeserializer}
 * (NOT the deprecated Jackson-2 variants) so the app stays on a single Jackson 3 classpath.
 *
 * Request side: a {@code @KafkaListener} consumes {@link AnimalInfoRequest} (value deserializer
 * wrapped in {@link ErrorHandlingDeserializer} so a malformed payload yields a null record value
 * plus a deserialization-exception header instead of throwing the container into retry storms).
 * Reply side: a {@link KafkaTemplate} publishes {@link AnimalInfoResponse} with no type-info header
 * (external systems must not be required to emit/read Spring Kafka type headers).
 */
@EnableKafka
@EnableConfigurationProperties(KafkaIntegrationProperties.class)
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, AnimalInfoRequest> animalInfoConsumerFactory(
            KafkaIntegrationProperties props, JsonMapper jsonMapper) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServersOrDefault());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.groupIdOrDefault());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.autoOffsetResetOrDefault());
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer swallows deserialization failures: value becomes null and the
        // exception is attached as a header (ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER),
        // so the listener can reply ERROR instead of the container looping on a poison pill.
        ErrorHandlingDeserializer<AnimalInfoRequest> valueDeserializer =
                new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(AnimalInfoRequest.class, jsonMapper));
        return new DefaultKafkaConsumerFactory<>(cfg, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "animalInfoListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, AnimalInfoRequest> animalInfoListenerContainerFactory(
            ConsumerFactory<String, AnimalInfoRequest> animalInfoConsumerFactory,
            KafkaIntegrationProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, AnimalInfoRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(animalInfoConsumerFactory);
        factory.setConcurrency(props.concurrencyOrDefault());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        // No retries (FixedBackOff(0, 0) => zero attempts); the default recoverer logs and stops. The
        // listener catches every exception itself and always replies, so this handler is only a
        // safety net — it must never redeliver a record.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }

    @Bean
    public ProducerFactory<String, AnimalInfoResponse> animalInfoProducerFactory(
            KafkaIntegrationProperties props, JsonMapper jsonMapper) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServersOrDefault());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, props.acksOrDefault());
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(cfg,
                new StringSerializer(),
                new JacksonJsonSerializer<AnimalInfoResponse>(jsonMapper).noTypeInfo());
    }

    @Bean(name = "animalInfoKafkaTemplate")
    public KafkaTemplate<String, AnimalInfoResponse> animalInfoKafkaTemplate(
            ProducerFactory<String, AnimalInfoResponse> animalInfoProducerFactory) {
        return new KafkaTemplate<>(animalInfoProducerFactory);
    }
}