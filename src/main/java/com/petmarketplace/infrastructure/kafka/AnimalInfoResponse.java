package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reply payload for every outcome. {@code status} is the discriminator; animal fields are null
 * unless {@code status == OK}. {@code correlationId} mirrors the request's Kafka header for
 * logging/debug. {@code listingStatus} is the listing's own status (distinct from {@code status},
 * the reply outcome).
 */
public record AnimalInfoResponse(
        ReplyStatus status,
        String correlationId,
        UUID listingId,
        String errorMessage,
        String title,
        String categoryName,
        String breedName,
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
        ListingStatus listingStatus
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** OK outcome populated by {@link AnimalInfoMapper#toOkResponse(Listing, String)}. */
    public static AnimalInfoResponse ok(
            String correlationId, UUID listingId, String title, String categoryName, String breedName,
            BigDecimal price, String currency, ListingGender gender, Integer ageMonths, String color,
            BigDecimal weightKg, String healthInfo, Boolean hasVaccination, Boolean hasDocuments,
            String locationCountry, String locationCity, ListingStatus listingStatus) {
        return new AnimalInfoResponse(
                ReplyStatus.OK, correlationId, listingId, null, title, categoryName, breedName,
                price, currency, gender, ageMonths, color, weightKg, healthInfo, hasVaccination,
                hasDocuments, locationCountry, locationCity, listingStatus);
    }

    /** Not found — listing missing, or present but not publicly visible. */
    public static AnimalInfoResponse notFound(String correlationId, UUID listingId) {
        return new AnimalInfoResponse(
                ReplyStatus.NOT_FOUND, correlationId, listingId, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Processing/deserialization error. errorMessage is a human-readable cause, never internal stack details. */
    public static AnimalInfoResponse error(String correlationId, UUID listingId, String errorMessage) {
        return new AnimalInfoResponse(
                ReplyStatus.ERROR, correlationId, listingId, errorMessage,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}