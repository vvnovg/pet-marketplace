package com.petmarketplace.application.message.dto;

import com.petmarketplace.application.user.dto.PublicProfileResponse;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        PublicProfileResponse sender,
        PublicProfileResponse receiver,
        MessageListingResponse listing,
        String content,
        String attachmentUrl,
        boolean read,
        Instant createdAt
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
