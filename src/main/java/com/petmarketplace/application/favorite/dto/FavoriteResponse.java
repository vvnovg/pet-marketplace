package com.petmarketplace.application.favorite.dto;

import com.petmarketplace.application.listing.dto.ListingMiniResponse;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record FavoriteResponse(
        UUID id,
        ListingMiniResponse listing,
        Instant createdAt
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
