package com.petmarketplace.infrastructure.notification.mail;

/** Снимок данных письма о сбросе пароля. */
public record PasswordResetMail(String recipientEmail, String displayName, String token) {
}
