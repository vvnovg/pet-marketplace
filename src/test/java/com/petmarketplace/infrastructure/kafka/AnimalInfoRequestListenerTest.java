package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;

class AnimalInfoRequestListenerTest {

    private final AnimalInfoService service = mock(AnimalInfoService.class);
    private final AnimalInfoRequestListener listener = new AnimalInfoRequestListener(service, null, null);

    private static final UUID LISTING_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String CORRELATION_ID = "corr-7";

    @Test
    void processShouldDelegateToServiceForValidRequest() {
        AnimalInfoResponse ok = AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null, null,
                null, null, null, null, null, null, null, null, null, null, ListingStatus.ACTIVE);
        when(service.findById(eq(LISTING_ID), eq(CORRELATION_ID))).thenReturn(ok);

        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(LISTING_ID), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.OK);
    }

    @Test
    void processShouldReturnErrorWhenListingIdMissing() {
        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(null), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void processShouldReturnErrorWhenRequestIsNull() {
        AnimalInfoResponse result = listener.process(null, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void processShouldReturnErrorWhenServiceThrows() {
        when(service.findById(eq(LISTING_ID), eq(CORRELATION_ID)))
                .thenThrow(new RuntimeException("boom"));

        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(LISTING_ID), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.listingId()).isEqualTo(LISTING_ID);
    }

    /**
     * Locks in the observability/containment guarantee for reply-send failures:
     * {@code KafkaTemplate.send()} is async, so a produce failure (broker down, serializer issue,
     * metadata timeout) surfaces on the returned {@link CompletableFuture}, not at the call site.
     * The listener must NOT propagate that failure (the {@code whenComplete} callback swallows it for
     * logging), and must still call {@code ack.acknowledge()} — per the no-redelivery contract we
     * accept the rare lost reply rather than duplicate it; the goal is observability, not prevention.
     */
    @Test
    void onRequestShouldNotPropagateSendFailureAndStillAck() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, AnimalInfoResponse> replyTemplate = mock(KafkaTemplate.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        AnimalInfoRequestListener withTemplate =
                new AnimalInfoRequestListener(service, replyTemplate, "test-reply-topic");

        // Service returns OK; the reply send fails asynchronously with a completed-failed future.
        AnimalInfoResponse ok = AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                null, null, null, null, null, null, null, null, null, null, null, ListingStatus.ACTIVE);
        when(service.findById(eq(LISTING_ID), eq(CORRELATION_ID))).thenReturn(ok);
        when(replyTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        ConsumerRecord<String, AnimalInfoRequest> record =
                new ConsumerRecord<>("test-request-topic", 0, 0L, null, new AnimalInfoRequest(LISTING_ID));
        record.headers().add(new RecordHeader(KafkaHeaders.CORRELATION_ID,
                CORRELATION_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // The send failure must be contained — the listener returns normally, no exception thrown.
        assertThatCode(() -> withTemplate.onRequest(record, ack)).doesNotThrowAnyException();
        // And the offset is still committed (no redelivery).
        verify(ack).acknowledge();
    }
}
