package com.petmarketplace.application.booking.dto;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record BookingListingResponse(
        UUID id,
        String title,
        BigDecimal price,
        String currency,
        String mainImageUrl,
        ListingStatus status
) {
}
