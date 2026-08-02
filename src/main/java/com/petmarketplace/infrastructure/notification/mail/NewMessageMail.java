package com.petmarketplace.infrastructure.notification.mail;

import java.util.UUID;

/** Снимок данных письма о новом сообщении в чате. */
public record NewMessageMail(
        String recipientEmail,
        String receiverName,
        String senderName,
        UUID senderId,
        String messageContent,
        boolean hasAttachment) {
}
