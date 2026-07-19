package com.petmarketplace.application.listing.dto;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import jakarta.validation.constraints.NotNull;

public record ListingStatusUpdateRequest(
        @NotNull ListingStatus status
) {
}
