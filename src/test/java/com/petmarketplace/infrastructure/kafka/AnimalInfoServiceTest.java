package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnimalInfoServiceTest {

    private final ListingRepository listingRepository = mock(ListingRepository.class);
    private final AnimalInfoMapper animalInfoMapper = mock(AnimalInfoMapper.class);
    private final AnimalInfoService service = new AnimalInfoService(listingRepository, animalInfoMapper);

    private static final UUID LISTING_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String CORRELATION_ID = "corr-1";

    @Test
    void shouldReturnOkForActiveListing() {
        Listing listing = mock(Listing.class);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));
        AnimalInfoResponse ok = AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                null, null, null, null, null, null, null, null, null, null, null, ListingStatus.ACTIVE);
        when(animalInfoMapper.toOkResponse(listing, CORRELATION_ID)).thenReturn(ok);

        AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.OK);
        verify(animalInfoMapper).toOkResponse(listing, CORRELATION_ID);
    }

    @Test
    void shouldReturnOkForReservedAndSold() {
        for (ListingStatus status : new ListingStatus[]{ListingStatus.RESERVED, ListingStatus.SOLD}) {
            Listing listing = mock(Listing.class);
            when(listing.getStatus()).thenReturn(status);
            when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));
            when(animalInfoMapper.toOkResponse(eq(listing), eq(CORRELATION_ID)))
                    .thenReturn(AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                            null, null, null, null, null, null, null, null, null, null, null, status));

            AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);
            assertThat(result.status()).isEqualTo(ReplyStatus.OK);
        }
    }

    @Test
    void shouldReturnNotFoundWhenListingMissing() {
        when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.empty());

        AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.NOT_FOUND);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(result.listingId()).isEqualTo(LISTING_ID);
        verify(animalInfoMapper, never()).toOkResponse(any(), any());
    }

    @Test
    void shouldReturnNotFoundForNonPublicStatus() {
        for (ListingStatus hidden : new ListingStatus[]{
                ListingStatus.DRAFT, ListingStatus.PENDING_MODERATION, ListingStatus.ARCHIVED, ListingStatus.REJECTED}) {
            Listing listing = mock(Listing.class);
            when(listing.getStatus()).thenReturn(hidden);
            when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));

            AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

            assertThat(result.status())
                    .as("non-public status %s must be reported as NOT_FOUND (no existence leak)", hidden)
                    .isEqualTo(ReplyStatus.NOT_FOUND);
            verify(animalInfoMapper, never()).toOkResponse(any(), eq(CORRELATION_ID));
        }
    }
}