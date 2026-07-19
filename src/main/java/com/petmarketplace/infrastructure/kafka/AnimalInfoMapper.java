package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import java.util.Locale;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Maps a {@link Listing} to an OK {@link AnimalInfoResponse}. Category/breed names are resolved to
 * Russian (the platform default — {@link LocalizedNameResolver#resolveLocale(String)} returns
 * Russian for a null language tag). The Kafka request carries no language preference, so a fixed
 * Russian locale is used; this can be made configurable later if needed.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE, imports = LocalizedNameResolver.class)
public interface AnimalInfoMapper {

    /**
     * Produce an OK response. {@code status} is forced to OK, {@code errorMessage} ignored (null),
     * {@code listingId} taken from the listing, {@code correlationId} from the parameter, and the
     * animal fields auto-mapped by name (title, price, currency, gender, ageMonths, color, weightKg,
     * healthInfo, hasVaccination, hasDocuments, locationCountry, locationCity).
     */
    @Mapping(target = "status", constant = "OK")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "listingId", source = "listing.id")
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "categoryName",
            expression = "java(LocalizedNameResolver.resolve(listing.getCategory().getNameRu(), listing.getCategory().getNameEn(), RUSSIAN))")
    @Mapping(target = "breedName",
            expression = "java(listing.getBreed() == null ? null : LocalizedNameResolver.resolve(listing.getBreed().getNameRu(), listing.getBreed().getNameEn(), RUSSIAN))")
    @Mapping(target = "listingStatus", source = "listing.status")
    AnimalInfoResponse toOkResponse(Listing listing, String correlationId);

    /** Fixed Russian locale (matches {@link LocalizedNameResolver}'s default for a null tag). */
    Locale RUSSIAN = LocalizedNameResolver.resolveLocale(null);
}