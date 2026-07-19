package com.petmarketplace.application.admin.controller;

import com.petmarketplace.application.admin.dto.AdminStatisticsResponse;
import com.petmarketplace.application.admin.dto.AdminUserResponse;
import com.petmarketplace.application.admin.dto.ListingModerateRequest;
import com.petmarketplace.application.admin.dto.UserRoleUpdateRequest;
import com.petmarketplace.application.admin.dto.UserStatusUpdateRequest;
import com.petmarketplace.application.admin.service.AdminService;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.review.dto.ReviewModerateRequest;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.domain.user.entity.Role;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
@Tag(name = "Administration", description = "Moderation and user management endpoints for administrators and moderators")
@SecurityRequirement(name = "bearer-jwt")
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "List and filter platform users")
    @ApiResponse(responseCode = "200", description = "Users retrieved")
    @GetMapping("/users")
    public Page<AdminUserResponse> listUsers(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) String search,
            @PageableDefault Pageable pageable) {
        return adminService.listUsers(role, active, verified, search, pageable);
    }

    @Operation(summary = "Change user active status")
    @ApiResponse(responseCode = "200", description = "Status updated")
    @ApiResponse(responseCode = "404", description = "User not found")
    @PutMapping("/users/{id}/status")
    public AdminUserResponse updateUserStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UserStatusUpdateRequest request) {
        return adminService.updateUserStatus(id, request);
    }

    @Operation(summary = "Change user role")
    @ApiResponse(responseCode = "200", description = "Role updated")
    @ApiResponse(responseCode = "404", description = "User not found")
    @PutMapping("/users/{id}/role")
    public AdminUserResponse updateUserRole(
            @PathVariable UUID id,
            @RequestBody @Valid UserRoleUpdateRequest request) {
        return adminService.updateUserRole(id, request);
    }

    @Operation(summary = "Get listings pending moderation")
    @ApiResponse(responseCode = "200", description = "Pending listings retrieved")
    @GetMapping("/listings/pending")
    public Page<ListingResponse> getPendingListings(
            @PageableDefault Pageable pageable,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return adminService.getPendingListings(pageable, locale);
    }

    @Operation(summary = "Moderate a listing (approve or reject)")
    @ApiResponse(responseCode = "200", description = "Listing moderated")
    @ApiResponse(responseCode = "400", description = "Invalid moderation status")
    @ApiResponse(responseCode = "404", description = "Listing not found")
    @PutMapping("/listings/{id}/moderate")
    public ListingResponse moderateListing(
            @PathVariable UUID id,
            @RequestBody @Valid ListingModerateRequest request,
            @Parameter(description = "Preferred language for category/breed names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return adminService.moderateListing(id, request, locale);
    }

    @Operation(summary = "Get reviews pending moderation")
    @ApiResponse(responseCode = "200", description = "Pending reviews retrieved")
    @GetMapping("/reviews/pending")
    public Page<ReviewResponse> getPendingReviews(@PageableDefault Pageable pageable) {
        return adminService.getPendingReviews(pageable);
    }

    @Operation(summary = "Moderate a review")
    @ApiResponse(responseCode = "200", description = "Review moderated")
    @ApiResponse(responseCode = "404", description = "Review not found")
    @PutMapping("/reviews/{id}/moderate")
    public ReviewResponse moderateReview(
            @PathVariable UUID id,
            @RequestBody @Valid ReviewModerateRequest request) {
        return adminService.moderateReview(id, request);
    }

    @Operation(summary = "Get platform statistics")
    @ApiResponse(responseCode = "200", description = "Statistics retrieved")
    @GetMapping("/statistics")
    public AdminStatisticsResponse getStatistics() {
        return adminService.getStatistics();
    }
}
