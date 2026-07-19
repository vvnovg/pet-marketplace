package com.petmarketplace.application.admin.dto;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import jakarta.validation.constraints.NotNull;

public record ListingModerateRequest(
        @NotNull ListingStatus status,
        String reason
) {
}
