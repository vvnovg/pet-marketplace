package com.petmarketplace.application.admin.dto;

import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import java.util.Map;

public record AdminStatisticsResponse(
        long totalUsers,
        long activeUsers,
        Map<ListingStatus, Long> listingsByStatus,
        Map<BookingStatus, Long> bookingsByStatus,
        Map<ReviewStatus, Long> reviewsByStatus,
        long listingsCreatedToday,
        long listingsCreatedThisWeek,
        long listingsCreatedThisMonth
) {
}
