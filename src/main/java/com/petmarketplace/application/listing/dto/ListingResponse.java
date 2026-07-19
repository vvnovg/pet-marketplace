package com.petmarketplace.application.listing.dto;

import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        PublicProfileResponse seller,
        UUID categoryId,
        String categoryName,
        UUID breedId,
        String breedName,
        String title,
        String description,
        BigDecimal price,
        String currency,
        ListingGender gender,
        Integer ageMonths,
        String color,
        BigDecimal weightKg,
        String healthInfo,
        Boolean hasVaccination,
        Boolean hasDocuments,
        String locationCountry,
        String locationCity,
        ListingStatus status,
        Integer viewsCount,
        List<ListingImageResponse> images,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ListingResponse {
        if (images == null) {
            images = List.of();
        }
    }
}
