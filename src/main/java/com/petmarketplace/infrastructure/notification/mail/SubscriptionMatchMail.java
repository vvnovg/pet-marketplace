package com.petmarketplace.infrastructure.notification.mail;

import java.math.BigDecimal;
import java.util.UUID;

/** Снимок данных письма о совпадении объявления с подпиской. */
public record SubscriptionMatchMail(
        String recipientEmail,
        String userName,
        UUID listingId,
        String listingTitle,
        BigDecimal listingPrice,
        String listingCurrency,
        String listingCity) {
}
