package com.petmarketplace.application.listing.service;

import com.petmarketplace.application.listing.dto.ListingSearchRequest;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.jpa.domain.Specification;

public final class ListingSpecification {

    private ListingSpecification() {
    }

    public static Specification<Listing> hasStatus(ListingStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Listing> hasCategory(UUID categoryId) {
        return categoryId == null ? null : (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Listing> hasBreed(UUID breedId) {
        return breedId == null ? null : (root, query, cb) -> cb.equal(root.get("breed").get("id"), breedId);
    }

    public static Specification<Listing> cityContains(String city) {
        return city == null || city.isBlank()
                ? null
                : (root, query, cb) -> cb.like(
                        cb.lower(root.get("locationCity")),
                        "%" + escapeLike(city.toLowerCase().trim()) + "%",
                        '\\');
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static Specification<Listing> priceBetween(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice == null && maxPrice == null) {
            return null;
        }
        return (root, query, cb) -> {
            if (minPrice != null && maxPrice != null) {
                return cb.between(root.get("price"), minPrice, maxPrice);
            }
            if (minPrice != null) {
                return cb.greaterThanOrEqualTo(root.get("price"), minPrice);
            }
            return cb.lessThanOrEqualTo(root.get("price"), maxPrice);
        };
    }

    public static Specification<Listing> genderEquals(ListingGender gender) {
        return gender == null ? null : (root, query, cb) -> cb.equal(root.get("gender"), gender);
    }

    public static Specification<Listing> ageBetween(Integer minAge, Integer maxAge) {
        if (minAge == null && maxAge == null) {
            return null;
        }
        return (root, query, cb) -> {
            if (minAge != null && maxAge != null) {
                return cb.between(root.get("ageMonths"), minAge, maxAge);
            }
            if (minAge != null) {
                return cb.greaterThanOrEqualTo(root.get("ageMonths"), minAge);
            }
            return cb.lessThanOrEqualTo(root.get("ageMonths"), maxAge);
        };
    }

    public static Specification<Listing> buildFromRequest(ListingSearchRequest request) {
        return Specification.allOf(Stream.of(
                        hasCategory(request.getCategoryId()),
                        hasBreed(request.getBreedId()),
                        cityContains(request.getCity()),
                        priceBetween(request.getMinPrice(), request.getMaxPrice()),
                        genderEquals(request.getGender()),
                        ageBetween(request.getMinAge(), request.getMaxAge()))
                .filter(Objects::nonNull)
                .toList());
    }
}
