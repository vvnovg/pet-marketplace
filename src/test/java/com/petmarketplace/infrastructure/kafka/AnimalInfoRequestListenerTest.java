package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
}
