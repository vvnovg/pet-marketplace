package com.petmarketplace.application.listing.dto;

import com.petmarketplace.domain.listing.entity.ListingGender;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ListingSearchRequest {

    private UUID categoryId;
    private UUID breedId;
    private String city;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private ListingGender gender;
    private Integer minAge;
    private Integer maxAge;
    private String sortBy = "createdAt";
    private String sortDirection = "DESC";
    private Integer page = 0;
    private Integer size = 20;
}
