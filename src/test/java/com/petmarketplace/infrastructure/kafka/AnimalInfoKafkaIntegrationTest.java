package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end Kafka request/reply integration test. Drives the flow with a manual
 * {@code KafkaTemplate} (request) + {@code KafkaConsumer} (reply poll loop via Awaitility),
 * covering OK (ACTIVE/RESERVED/SOLD), NOT_FOUND (unknown id + PENDING_MODERATION listing), and
 * ERROR (malformed JSON body) outcomes, and asserting the {@code correlationId} header round-trips.
 *
 * <p>Runs in both embedded (Testcontainers Kafka started by {@link IntegrationTestBase}) and stand
 * modes — the bootstrap servers come from {@code kafka.bootstrap-servers}, which the base class
 * binds to the shared Testcontainers broker in embedded mode and the stand profile supplies in
 * stand mode.
 */
class AnimalInfoKafkaIntegrationTest extends IntegrationTestBase {

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${kafka.topics.request}")
    private String requestTopic;
    @Value("${kafka.topics.reply}")
    private String replyTopic;

    private KafkaTemplate<String, AnimalInfoRequest> requestTemplate;
    private KafkaConsumer<String, AnimalInfoResponse> replyConsumer;
    // DefaultKafkaProducerFactory (not the ProducerFactory interface) so we can call destroy()
    // for cleanup — spring-kafka 4's ProducerFactory interface no longer extends AutoCloseable.
    private DefaultKafkaProducerFactory<String, AnimalInfoRequest> requestProducerFactory;

    @BeforeEach
    void setUpKafkaClients(@Autowired JsonMapper jsonMapper) {
        // Request producer: String keys, AnimalInfoRequest values, no type-info header on the wire
        // (mirrors KafkaConfig's animalInfoProducerFactory: JacksonJsonSerializer(...).noTypeInfo()).
        Map<String, Object> producerCfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        // Type-witness <AnimalInfoRequest> mirrors KafkaConfig's producer factory so the noTypeInfo()
        // chain resolves cleanly against the same constructor form Task 3 validated.
        requestProducerFactory = new DefaultKafkaProducerFactory<>(
                producerCfg, new StringSerializer(),
                new JacksonJsonSerializer<AnimalInfoRequest>(jsonMapper).noTypeInfo());
        requestTemplate = new KafkaTemplate<>(requestProducerFactory);

        // Reply consumer: unique group, manual assign + seekToBeginning so we never miss a reply
        // produced before this consumer's first poll. The deserializer is the SAME class-targeted
        // constructor KafkaConfig uses (JacksonJsonDeserializer(Class, JsonMapper)).
        Map<String, Object> consumerCfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reply-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        replyConsumer = new KafkaConsumer<>(consumerCfg,
                new StringDeserializer(),
                new JacksonJsonDeserializer<>(AnimalInfoResponse.class, jsonMapper));
    }

    @AfterEach
    void closeClients() {
        if (replyConsumer != null) replyConsumer.close();
        // DefaultKafkaProducerFactory implements DisposableBean; destroy() closes the underlying
        // Kafka Producer(s). The ProducerFactory interface itself has no close() in spring-kafka 4.
        if (requestProducerFactory != null) requestProducerFactory.destroy();
    }

    @Test
    void shouldReplyOkForActiveListing() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);
        ListingResponse listing = createActiveListing(seller, admin);

        AnimalInfoResponse reply = requestAndAwaitReply(listing.id());

        assertThat(reply.status()).isEqualTo(ReplyStatus.OK);
        assertThat(reply.listingId()).isEqualTo(listing.id());
        assertThat(reply.title()).isEqualTo("Test puppy");
        assertThat(reply.categoryName()).isEqualTo("Собаки");
        assertThat(reply.breedName()).isEqualTo("Лабрадор-ретривер");
        assertThat(reply.listingStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reply.price()).isEqualByComparingTo(BigDecimal.valueOf(30000));
        assertThat(reply.currency()).isEqualTo("RUB");
        assertThat(reply.gender()).isEqualTo(ListingGender.MALE);
        assertThat(reply.hasVaccination()).isTrue();
        assertThat(reply.hasDocuments()).isTrue();
        assertThat(reply.locationCity()).isEqualTo("Moscow");
        assertThat(reply.locationCountry()).isEqualTo("Russia");
    }

    @Test
    void shouldReplyOkForReservedAndSoldListings() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser buyer = createUniqueUser(Role.BUYER);
        // createActiveListing returns ACTIVE. The admin moderate endpoint only accepts
        // PENDING_MODERATION/REJECTED → ACTIVE/REJECTED, so RESERVED/SOLD must be reached via the
        // booking lifecycle: buyer books → seller confirms (listing → RESERVED) → seller completes
        // (listing → SOLD). Both states are publicly visible, so AnimalInfoService replies OK.
        ListingResponse active = createActiveListing(seller, admin);

        // Buyer requests a booking (PENDING).
        var booking = restClient.post().uri("/listings/" + active.id() + "/book")
                .headers(authHeaders(buyer))
                .retrieve().toEntity(com.petmarketplace.application.booking.dto.BookingResponse.class).getBody();
        assertThat(booking).isNotNull();
        UUID bookingId = booking.id();

        // Seller confirms → listing RESERVED.
        restClient.put().uri("/bookings/" + bookingId + "/confirm")
                .headers(authHeaders(seller))
                .retrieve().toEntity(com.petmarketplace.application.booking.dto.BookingResponse.class);
        AnimalInfoResponse reservedReply = requestAndAwaitReply(active.id());
        assertThat(reservedReply.status()).isEqualTo(ReplyStatus.OK);
        assertThat(reservedReply.listingStatus()).isEqualTo(ListingStatus.RESERVED);

        // Seller completes → listing SOLD.
        restClient.put().uri("/bookings/" + bookingId + "/complete")
                .headers(authHeaders(seller))
                .retrieve().toEntity(com.petmarketplace.application.booking.dto.BookingResponse.class);
        AnimalInfoResponse soldReply = requestAndAwaitReply(active.id());
        assertThat(soldReply.status()).isEqualTo(ReplyStatus.OK);
        assertThat(soldReply.listingStatus()).isEqualTo(ListingStatus.SOLD);
    }

    @Test
    void shouldReplyNotFoundForUnknownListingId() {
        UUID unknown = UUID.randomUUID();
        AnimalInfoResponse reply = requestAndAwaitReply(unknown);
        assertThat(reply.status()).isEqualTo(ReplyStatus.NOT_FOUND);
        assertThat(reply.listingId()).isEqualTo(unknown);
    }

    @Test
    void shouldReplyNotFoundForPendingModerationListing() {
        TestUser seller = createUniqueUser(Role.SELLER);
        // Create via REST but do NOT moderate. ListingService.create sets PENDING_MODERATION (the
        // visibility rule in Task 5 treats both DRAFT and PENDING_MODERATION as NOT_FOUND, so the
        // test's intent — a non-public listing yields NOT_FOUND — holds regardless).
        ListingCreateRequest createRequest = new ListingCreateRequest(
                DOGS_CATEGORY_ID, LABRADOR_BREED_ID, "Draft pet", "desc",
                BigDecimal.valueOf(1000), "RUB", ListingGender.FEMALE, 2, "White",
                BigDecimal.valueOf(5.0), "ok", false, false, "Russia", "Kazan");
        ListingResponse draft = restClient.post().uri("/listings")
                .body(createRequest).headers(authHeaders(seller))
                .retrieve().toEntity(ListingResponse.class).getBody();
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo(ListingStatus.PENDING_MODERATION);

        AnimalInfoResponse reply = requestAndAwaitReply(draft.id());
        assertThat(reply.status()).isEqualTo(ReplyStatus.NOT_FOUND);
    }

    @Test
    void shouldReplyErrorForMalformedRequestBody() {
        // Produce raw bytes that are not valid JSON for AnimalInfoRequest. The listener's
        // ErrorHandlingDeserializer will set value=null + attach the exception header, and the
        // listener replies ERROR (see AnimalInfoRequestListener: record.value() == null branch).
        String correlationId = UUID.randomUUID().toString();
        DefaultKafkaProducerFactory<String, String> badFactory =
                new DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                               ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                        new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> badTemplate = new KafkaTemplate<>(badFactory);
        // Explicit (Integer)/(String) casts on the nulls disambiguate the 5-arg ProducerRecord
        // overload from (String, Integer, Long, K, V) — same fix AnimalInfoRequestListener uses.
        badTemplate.send(new ProducerRecord<String, String>(
                requestTopic,
                (Integer) null,
                (String) null,
                "this is not json",
                List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID,
                        correlationId.getBytes(StandardCharsets.UTF_8)))));
        badTemplate.flush();
        badFactory.destroy();

        AnimalInfoResponse reply = awaitReply(correlationId, Duration.ofSeconds(20));
        assertThat(reply.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(reply.correlationId()).isEqualTo(correlationId);
    }

    /** Produce an AnimalInfoRequest with the given correlationId header and poll the reply topic. */
    private AnimalInfoResponse requestAndAwaitReply(UUID listingId) {
        String correlationId = UUID.randomUUID().toString();
        ProducerRecord<String, AnimalInfoRequest> request = new ProducerRecord<>(
                requestTopic,
                (Integer) null,
                (String) null,
                new AnimalInfoRequest(listingId),
                List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID,
                        correlationId.getBytes(StandardCharsets.UTF_8))));
        requestTemplate.send(request);
        requestTemplate.flush();
        return awaitReply(correlationId, Duration.ofSeconds(20));
    }

    /**
     * Manually assign reply partition 0, seek to beginning, then poll until a matching reply
     * arrives. seekToBeginning (after manual assignment, before the first poll) ensures we never
     * miss a reply produced before this consumer subscribed — the listener may publish the reply
     * faster than this consumer's first poll, and with auto.offset.reset=earliest + manual assign
     * the offset is pinned to the start of the partition so every reply is visible.
     */
    private AnimalInfoResponse awaitReply(String correlationId, Duration timeout) {
        org.apache.kafka.common.TopicPartition partition =
                new org.apache.kafka.common.TopicPartition(replyTopic, 0);
        replyConsumer.assign(List.of(partition));
        replyConsumer.seekToBeginning(List.of(partition));
        AtomicReference<AnimalInfoResponse> found = new AtomicReference<>();
        await().atMost(timeout).until(() -> {
            for (ConsumerRecord<String, AnimalInfoResponse> rec : replyConsumer.poll(Duration.ofSeconds(2))) {
                // Headers.lastHeader(String) returns Header (NOT String) — read its value bytes and
                // decode as UTF-8 to compare against the request's correlationId.
                Header recCorr = rec.headers().lastHeader(KafkaHeaders.CORRELATION_ID);
                if (recCorr != null && correlationId.equals(new String(recCorr.value(), StandardCharsets.UTF_8))) {
                    found.set(rec.value());
                    return true;
                }
            }
            return false;
        });
        AnimalInfoResponse r = found.get();
        assertThat(r).as("reply for correlationId %s", correlationId).isNotNull();
        return r;
    }
}