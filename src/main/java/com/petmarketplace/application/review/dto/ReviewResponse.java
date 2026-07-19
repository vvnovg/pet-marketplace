package com.petmarketplace.application.review.dto;

import com.petmarketplace.application.booking.dto.BookingListingResponse;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        PublicProfileResponse author,
        PublicProfileResponse recipient,
        BookingListingResponse booking,
        int rating,
        String comment,
        ReviewStatus status,
        Instant createdAt
) {
}
