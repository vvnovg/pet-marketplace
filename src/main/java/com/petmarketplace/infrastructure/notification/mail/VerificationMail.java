package com.petmarketplace.infrastructure.notification.mail;

/** Снимок данных письма о подтверждении адреса. */
public record VerificationMail(String recipientEmail, String displayName, String token) {
}
