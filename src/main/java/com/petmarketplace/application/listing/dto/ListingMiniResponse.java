package com.petmarketplace.application.listing.dto;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

public record ListingMiniResponse(
        UUID id,
        String title,
        BigDecimal price,
        String currency,
        String locationCity,
        String mainImageUrl,
        ListingStatus status
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
