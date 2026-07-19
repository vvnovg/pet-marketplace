package com.petmarketplace.application.listing.controller;

import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.booking.service.BookingService;
import com.petmarketplace.application.favorite.service.FavoriteService;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingImageResponse;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.listing.dto.ListingSearchRequest;
import com.petmarketplace.application.listing.dto.ListingStatusUpdateRequest;
import com.petmarketplace.application.listing.service.ListingService;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
@Tag(name = "Listings", description = "Pet sale advertisements: search, create, update, images, favorites and bookings")
public class ListingController {

    private final ListingService listingService;
    private final BookingService bookingService;
    private final FavoriteService favoriteService;

    @Operation(summary = "Search and filter active listings")
    @ApiResponse(responseCode = "200", description = "Listings found")
    @GetMapping
    public Page<ListingResponse> search(
            @ModelAttribute ListingSearchRequest request,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return listingService.searchListings(request, locale);
    }

    @Operation(summary = "Get listing details by id")
    @ApiResponse(responseCode = "200", description = "Listing found")
    @ApiResponse(responseCode = "404", description = "Listing not found or not active")
    @GetMapping("/{id}")
    public ListingResponse getById(
            @PathVariable UUID id,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return listingService.getListingById(id, locale);
    }

    @Operation(summary = "Create a new listing (SELLER only)")
    @ApiResponse(responseCode = "201", description = "Listing created and sent to moderation")
    @ApiResponse(responseCode = "400", description = "Invalid listing data")
    @ApiResponse(responseCode = "403", description = "Insufficient role")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public ListingResponse create(
            @RequestBody @Valid ListingCreateRequest request,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return listingService.createListing(request, locale);
    }

    @Operation(summary = "Update own listing")
    @ApiResponse(responseCode = "200", description = "Listing updated")
    @ApiResponse(responseCode = "403", description = "Not allowed to modify this listing")
    @ApiResponse(responseCode = "404", description = "Listing not found")
    @SecurityRequirement(name = "bearer-jwt")
    @PutMapping("/{id}")
    public ListingResponse update(
            @PathVariable UUID id,
            @RequestBody @Valid ListingCreateRequest request,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return listingService.updateListing(id, request, locale);
    }

    @Operation(summary = "Delete own listing")
    @ApiResponse(responseCode = "204", description = "Listing deleted")
    @ApiResponse(responseCode = "403", description = "Not allowed to delete this listing")
    @ApiResponse(responseCode = "404", description = "Listing not found")
    @SecurityRequirement(name = "bearer-jwt")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        listingService.deleteListing(id);
    }

    @Operation(summary = "Upload an image to a listing")
    @ApiResponse(responseCode = "201", description = "Image uploaded")
    @ApiResponse(responseCode = "400", description = "Invalid image")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingImageResponse uploadImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return listingService.uploadImage(id, file);
    }

    @Operation(summary = "Delete an image from a listing")
    @ApiResponse(responseCode = "204", description = "Image deleted")
    @SecurityRequirement(name = "bearer-jwt")
    @DeleteMapping("/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
            @PathVariable UUID id,
            @PathVariable UUID imageId) {
        listingService.deleteImage(id, imageId);
    }

    @Operation(summary = "Add listing to favorites")
    @ApiResponse(responseCode = "200", description = "Added to favorites")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.OK)
    public void addToFavorites(@PathVariable UUID id) {
        favoriteService.addToFavorites(id);
    }

    @Operation(summary = "Remove listing from favorites")
    @ApiResponse(responseCode = "204", description = "Removed from favorites")
    @SecurityRequirement(name = "bearer-jwt")
    @DeleteMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromFavorites(@PathVariable UUID id) {
        favoriteService.removeFromFavorites(id);
    }

    @Operation(summary = "Request a booking for an active listing")
    @ApiResponse(responseCode = "201", description = "Booking created")
    @ApiResponse(responseCode = "400", description = "Listing not active or booking already exists")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/{id}/book")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse book(@PathVariable UUID id,
                                @RequestParam(required = false) String message) {
        return bookingService.createBooking(id, message);
    }

    @Operation(summary = "Update listing status (owner or admin)")
    @ApiResponse(responseCode = "200", description = "Status updated")
    @ApiResponse(responseCode = "403", description = "Not allowed")
    @SecurityRequirement(name = "bearer-jwt")
    @PutMapping("/{id}/status")
    public ListingResponse updateStatus(
            @PathVariable UUID id,
            @RequestBody @Valid ListingStatusUpdateRequest request,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return listingService.updateStatus(id, request, locale);
    }
}
