package com.petmarketplace.application.message.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record MessageSendRequest(
        @NotNull UUID receiverId,
        UUID listingId,
        @Size(max = 2000) String content
) {
}
