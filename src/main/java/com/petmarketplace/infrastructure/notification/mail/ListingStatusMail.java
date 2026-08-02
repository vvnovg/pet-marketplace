package com.petmarketplace.infrastructure.notification.mail;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.util.UUID;

/** Снимок данных письма о смене статуса объявления. */
public record ListingStatusMail(
        String recipientEmail,
        String sellerName,
        UUID listingId,
        String listingTitle,
        ListingStatus status) {
}
