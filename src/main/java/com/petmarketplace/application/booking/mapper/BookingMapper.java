package com.petmarketplace.application.booking.mapper;

import com.petmarketplace.application.booking.dto.BookingDealSummaryResponse;
import com.petmarketplace.application.booking.dto.BookingListingResponse;
import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingImage;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class BookingMapper {

    public BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                mapListing(booking.getListing()),
                mapUser(booking.getBuyer()),
                mapUser(booking.getSeller()),
                booking.getStatus(),
                booking.getMessage(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }

    public BookingDealSummaryResponse toDealSummary(Booking booking) {
        return new BookingDealSummaryResponse(
                booking.getId(),
                mapListing(booking.getListing()),
                mapUser(booking.getBuyer()),
                mapUser(booking.getSeller()),
                booking.getStatus(),
                booking.getMessage(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }

    private BookingListingResponse mapListing(Listing listing) {
        if (listing == null) {
            return null;
        }
        return new BookingListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getPrice(),
                listing.getCurrency(),
                resolveMainImageUrl(listing),
                listing.getStatus()
        );
    }

    private String resolveMainImageUrl(Listing listing) {
        if (listing.getImages() == null || listing.getImages().isEmpty()) {
            return null;
        }
        return listing.getImages().stream()
                .filter(image -> Boolean.TRUE.equals(image.getIsMain()))
                .findFirst()
                .map(ListingImage::getUrl)
                .orElseGet(() -> listing.getImages().get(0).getUrl());
    }

    private PublicProfileResponse mapUser(User user) {
        if (user == null) {
            return null;
        }
        Profile profile = user.getProfile();
        return new PublicProfileResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getAvatarUrl(),
                profile != null ? profile.getBio() : null,
                profile != null ? profile.getCountry() : null,
                profile != null ? profile.getCity() : null,
                profile != null ? profile.getRating() : null,
                profile != null ? profile.getTotalReviews() : null,
                user.getRole()
        );
    }
}
