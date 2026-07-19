package com.petmarketplace.application.listing.mapper;

import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingImageResponse;
import com.petmarketplace.application.listing.dto.ListingMiniResponse;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingImage;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ListingMapper {

    @Mapping(source = "category.id", target = "categoryId")
    @Mapping(source = "breed.id", target = "breedId")
    @Mapping(
            target = "categoryName",
            expression = "java(com.petmarketplace.infrastructure.localization.LocalizedNameResolver.resolve(listing.getCategory().getNameRu(), listing.getCategory().getNameEn(), locale))"
    )
    @Mapping(
            target = "breedName",
            expression = "java(listing.getBreed() == null ? null : com.petmarketplace.infrastructure.localization.LocalizedNameResolver.resolve(listing.getBreed().getNameRu(), listing.getBreed().getNameEn(), locale))"
    )
    @Mapping(target = "seller", expression = "java(mapSeller(listing.getSeller(), sellerProfile))")
    @Mapping(target = "images", expression = "java(mapImages(listing.getImages()))")
    ListingResponse toListingResponse(Listing listing, @Context Profile sellerProfile, @Context Locale locale);

    ListingImageResponse toImageResponse(ListingImage image);

    default ListingMiniResponse toMiniResponse(Listing listing) {
        if (listing == null) {
            return null;
        }
        String mainImageUrl = listing.getImages().stream()
                .filter(img -> Boolean.TRUE.equals(img.getIsMain()))
                .findFirst()
                .map(ListingImage::getUrl)
                .orElseGet(() -> listing.getImages().stream()
                        .findFirst()
                        .map(ListingImage::getUrl)
                        .orElse(null));
        return new ListingMiniResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getPrice(),
                listing.getCurrency(),
                listing.getLocationCity(),
                mainImageUrl,
                listing.getStatus()
        );
    }

    default Listing toEntity(ListingCreateRequest request) {
        return Listing.builder()
                .title(request.title())
                .description(request.description())
                .price(request.price())
                .currency(request.currency())
                .gender(request.gender())
                .ageMonths(request.ageMonths())
                .color(request.color())
                .weightKg(request.weightKg())
                .healthInfo(request.healthInfo())
                .hasVaccination(request.hasVaccination() != null ? request.hasVaccination() : false)
                .hasDocuments(request.hasDocuments() != null ? request.hasDocuments() : false)
                .locationCountry(request.locationCountry())
                .locationCity(request.locationCity())
                .build();
    }

    default void updateListingFromRequest(ListingCreateRequest request, @MappingTarget Listing listing) {
        listing.setTitle(request.title());
        listing.setDescription(request.description());
        listing.setPrice(request.price());
        listing.setCurrency(request.currency());
        listing.setGender(request.gender());
        listing.setAgeMonths(request.ageMonths());
        listing.setColor(request.color());
        listing.setWeightKg(request.weightKg());
        listing.setHealthInfo(request.healthInfo());
        if (request.hasVaccination() != null) {
            listing.setHasVaccination(request.hasVaccination());
        }
        if (request.hasDocuments() != null) {
            listing.setHasDocuments(request.hasDocuments());
        }
        listing.setLocationCountry(request.locationCountry());
        listing.setLocationCity(request.locationCity());
    }

    default PublicProfileResponse mapSeller(User seller, @Context Profile sellerProfile) {
        if (seller == null) {
            return null;
        }
        return new PublicProfileResponse(
                seller.getId(),
                seller.getFirstName(),
                seller.getLastName(),
                seller.getAvatarUrl(),
                sellerProfile != null ? sellerProfile.getBio() : null,
                sellerProfile != null ? sellerProfile.getCountry() : null,
                sellerProfile != null ? sellerProfile.getCity() : null,
                sellerProfile != null ? sellerProfile.getRating() : null,
                sellerProfile != null ? sellerProfile.getTotalReviews() : null,
                seller.getRole()
        );
    }

    default List<ListingImageResponse> mapImages(List<ListingImage> images) {
        if (images == null) {
            return List.of();
        }
        return images.stream()
                .sorted(Comparator.comparingInt(ListingImage::getOrderIndex))
                .map(this::toImageResponse)
                .toList();
    }
}
