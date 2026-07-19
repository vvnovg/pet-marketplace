package com.petmarketplace.application.subscription.dto;

import com.petmarketplace.domain.listing.entity.ListingGender;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

public record SubscriptionCreateRequest(
        UUID categoryId,
        UUID breedId,
        String city,
        @PositiveOrZero BigDecimal minPrice,
        @PositiveOrZero BigDecimal maxPrice,
        ListingGender gender,
        Integer minAge,
        Integer maxAge,
        Boolean hasVaccination,
        Boolean hasDocuments
) {
}
