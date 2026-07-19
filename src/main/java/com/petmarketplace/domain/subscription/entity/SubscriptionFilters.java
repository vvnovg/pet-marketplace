package com.petmarketplace.domain.subscription.entity;

import com.petmarketplace.domain.listing.entity.ListingGender;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionFilters {

    private UUID categoryId;
    private UUID breedId;
    private String city;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private ListingGender gender;
    private Integer minAge;
    private Integer maxAge;
    private Boolean hasVaccination;
    private Boolean hasDocuments;
}
