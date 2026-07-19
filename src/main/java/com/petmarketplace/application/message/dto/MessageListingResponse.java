package com.petmarketplace.application.message.dto;

import java.io.Serializable;
import java.util.UUID;

public record MessageListingResponse(
        UUID id,
        String title,
        String mainImageUrl
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
