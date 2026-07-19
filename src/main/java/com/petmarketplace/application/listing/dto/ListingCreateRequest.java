package com.petmarketplace.application.listing.dto;

import com.petmarketplace.domain.listing.entity.ListingGender;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record ListingCreateRequest(
        @NotNull UUID categoryId,
        UUID breedId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 4000) String description,
        @NotNull @Positive BigDecimal price,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull ListingGender gender,
        @NotNull @Min(0) Integer ageMonths,
        @Size(max = 100) String color,
        @Positive BigDecimal weightKg,
        @Size(max = 2000) String healthInfo,
        Boolean hasVaccination,
        Boolean hasDocuments,
        @Size(max = 100) String locationCountry,
        @Size(max = 100) String locationCity
) {
}
