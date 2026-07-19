package com.petmarketplace.application.booking.dto;

import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public record BookingDealSummaryResponse(
        UUID id,
        BookingListingResponse listing,
        PublicProfileResponse buyer,
        PublicProfileResponse seller,
        BookingStatus status,
        String message,
        Instant createdAt,
        Instant updatedAt
) {
}
