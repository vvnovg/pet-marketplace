package com.petmarketplace.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Kafka integration settings for the animal-info request/reply flow, externalized under the
 * {@code kafka.*} namespace (kept separate from Boot's own {@code spring.kafka.*} so the app's
 * future Kafka use is not pre-empted). Bound via {@code @EnableConfigurationProperties} on
 * {@link KafkaConfig}. Constructor binding on a record — no setters.
 */
@ConfigurationProperties(prefix = "kafka")
public record KafkaIntegrationProperties(
        String bootstrapServers,
        Consumer consumer,
        Producer producer,
        Topics topics
) {

    public record Consumer(String groupId, String autoOffsetReset, Integer concurrency) {
    }

    public record Producer(String acks) {
    }

    public record Topics(String request, String reply) {
    }

    /** Defensive defaults so downstream code can treat absent config as "use the documented default". */
    public String bootstrapServersOrDefault() {
        return bootstrapServers != null ? bootstrapServers : "localhost:9092";
    }

    public String groupIdOrDefault() {
        return consumer != null && consumer.groupId != null ? consumer.groupId : "pet-marketplace-animal-info";
    }

    public String autoOffsetResetOrDefault() {
        return consumer != null && consumer.autoOffsetReset != null ? consumer.autoOffsetReset : "earliest";
    }

    public int concurrencyOrDefault() {
        return consumer != null && consumer.concurrency != null ? consumer.concurrency : 1;
    }

    public String acksOrDefault() {
        return producer != null && producer.acks != null ? producer.acks : "all";
    }
}