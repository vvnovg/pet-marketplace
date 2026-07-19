package com.petmarketplace.application.message.dto;

import com.petmarketplace.application.user.dto.PublicProfileResponse;
import java.io.Serializable;

public record ConversationResponse(
        PublicProfileResponse partner,
        MessageResponse lastMessage,
        long unreadCount
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
