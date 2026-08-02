package com.petmarketplace.infrastructure.notification.mail;

import com.petmarketplace.domain.booking.entity.BookingStatus;
import java.util.UUID;

/** Снимок данных письма о смене статуса брони. */
public record BookingStatusMail(
        String recipientEmail,
        String participantName,
        String otherPartyName,
        UUID bookingId,
        String listingTitle,
        BookingStatus status,
        boolean buyer) {
}
