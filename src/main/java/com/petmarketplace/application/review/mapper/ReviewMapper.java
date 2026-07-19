package com.petmarketplace.application.review.mapper;

import com.petmarketplace.application.booking.dto.BookingListingResponse;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingImage;
import com.petmarketplace.domain.review.entity.Review;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class ReviewMapper {

    public ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.getId(),
                mapUser(review.getAuthor()),
                mapUser(review.getRecipient()),
                mapBooking(review.getBooking()),
                review.getRating(),
                review.getComment(),
                review.getStatus(),
                review.getCreatedAt()
        );
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

    private BookingListingResponse mapBooking(Booking booking) {
        if (booking == null) {
            return null;
        }
        Listing listing = booking.getListing();
        if (listing == null) {
            return new BookingListingResponse(booking.getId(), null, null, null, null, null);
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
}
