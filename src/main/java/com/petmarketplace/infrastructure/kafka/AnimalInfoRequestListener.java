package com.petmarketplace.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumes {@link AnimalInfoRequest} from the request topic, looks up the animal, and publishes an
 * {@link AnimalInfoResponse} to the reply topic with the request's {@code correlationId} header
 * copied verbatim. Never rethrows to the container — every path ends in a reply, so the container
 * never retries/redelivers.
 */
@Component
public class AnimalInfoRequestListener {

    private static final Logger log = LoggerFactory.getLogger(AnimalInfoRequestListener.class);

    private final AnimalInfoService animalInfoService;
    private final KafkaTemplate<String, AnimalInfoResponse> replyKafkaTemplate;
    private final String replyTopic;

    public AnimalInfoRequestListener(
            AnimalInfoService animalInfoService,
            @org.springframework.beans.factory.annotation.Qualifier("animalInfoKafkaTemplate")
            KafkaTemplate<String, AnimalInfoResponse> replyKafkaTemplate,
            @Value("${kafka.topics.reply}") String replyTopic) {
        this.animalInfoService = animalInfoService;
        this.replyKafkaTemplate = replyKafkaTemplate;
        this.replyTopic = replyTopic;
    }

    /**
     * Core, unit-testable handling: turn a (possibly null) request + correlationId into a response.
     * Never throws — converts every failure into an ERROR response.
     */
    AnimalInfoResponse process(AnimalInfoRequest request, String correlationId) {
        try {
            if (request == null || request.listingId() == null) {
                return AnimalInfoResponse.error(correlationId,
                        request != null ? request.listingId() : null,
                        "Missing listingId");
            }
            return animalInfoService.findById(request.listingId(), correlationId);
        } catch (RuntimeException e) {
            log.error("Unexpected error processing animal-info request correlationId={} listingId={}",
                    correlationId, request != null ? request.listingId() : null, e);
            return AnimalInfoResponse.error(correlationId,
                    request != null ? request.listingId() : null,
                    "Processing error");
        }
    }

    @KafkaListener(
            topics = "${kafka.topics.request}",
            groupId = "${kafka.consumer.group-id}",
            containerFactory = "animalInfoListenerContainerFactory")
    public void onRequest(ConsumerRecord<String, AnimalInfoRequest> record,
                          org.springframework.kafka.support.Acknowledgment ack) {
        String correlationId = readCorrelationId(record);
        UUID safeListingId = record.value() != null ? record.value().listingId() : null;

        // Deserialization failure: ErrorHandlingDeserializer set value=null and attached the
        // exception as a header. Reply ERROR with the (possibly generated) correlationId.
        if (record.value() == null
                && record.headers().lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER) != null) {
            log.warn("Failed to deserialize animal-info request correlationId={}", correlationId);
            sendReply(correlationId,
                    AnimalInfoResponse.error(correlationId, null, "Failed to deserialize request"));
            ack.acknowledge();
            return;
        }

        AnimalInfoResponse response = process(record.value(), correlationId);
        switch (response.status()) {
            case OK -> log.info("Animal-info OK correlationId={} listingId={}", correlationId, safeListingId);
            case NOT_FOUND -> log.warn("Animal-info NOT_FOUND correlationId={} listingId={}", correlationId, safeListingId);
            case ERROR -> log.error("Animal-info ERROR correlationId={} listingId={} message={}",
                    correlationId, safeListingId, response.errorMessage());
        }
        sendReply(correlationId, response);
        ack.acknowledge();
    }

    private String readCorrelationId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.CORRELATION_ID);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        // No correlation header (malformed producer): mint one so a reply is never dropped.
        String generated = UUID.randomUUID().toString();
        log.warn("Animal-info request had no {} header; generated correlationId={}",
                KafkaHeaders.CORRELATION_ID, generated);
        return generated;
    }

    private void sendReply(String correlationId, AnimalInfoResponse response) {
        org.apache.kafka.clients.producer.ProducerRecord<String, AnimalInfoResponse> reply =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        replyTopic, (Integer) null, (String) null, response,
                        List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID,
                                correlationId.getBytes(StandardCharsets.UTF_8))));
        replyKafkaTemplate.send(reply);
    }
}