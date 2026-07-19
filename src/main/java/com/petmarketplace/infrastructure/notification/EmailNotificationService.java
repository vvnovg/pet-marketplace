package com.petmarketplace.infrastructure.notification;

import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.message.entity.Message;
import com.petmarketplace.domain.user.entity.User;

/**
 * Sends email notifications to users about registration, messages, listings, bookings and subscriptions.
 */
public interface EmailNotificationService {

    void sendVerificationEmail(User user, String token);

    void sendPasswordResetEmail(User user, String token);

    void sendNewMessageNotification(User receiver, User sender, Message message);

    void sendListingStatusUpdate(User seller, Listing listing, ListingStatus status);

    void sendBookingStatusUpdate(User participant, Booking booking, BookingStatus status);

    void sendSubscriptionMatch(User user, Listing listing);
}
