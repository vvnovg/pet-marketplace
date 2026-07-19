package com.petmarketplace.application.user.controller;

import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.application.review.service.ReviewService;
import com.petmarketplace.application.user.dto.ProfileUpdateRequest;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.application.user.dto.UserProfileResponse;
import com.petmarketplace.application.user.service.ProfileService;
import com.petmarketplace.application.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Current profile and public user profiles")
public class UserController {

    private final ProfileService profileService;
    private final UserService userService;
    private final ReviewService reviewService;

    @Operation(summary = "Get current user profile")
    @ApiResponse(responseCode = "200", description = "Profile retrieved")
    @SecurityRequirement(name = "bearer-jwt")
    @GetMapping("/me")
    public UserProfileResponse getCurrentProfile() {
        return profileService.getCurrentProfile();
    }

    @Operation(summary = "Update current user profile")
    @ApiResponse(responseCode = "200", description = "Profile updated")
    @ApiResponse(responseCode = "400", description = "Invalid profile data")
    @SecurityRequirement(name = "bearer-jwt")
    @PutMapping("/me")
    public UserProfileResponse updateCurrentProfile(@RequestBody @Valid ProfileUpdateRequest request) {
        return profileService.updateCurrentProfile(request);
    }

    @Operation(summary = "Upload current user avatar")
    @ApiResponse(responseCode = "200", description = "Avatar uploaded")
    @ApiResponse(responseCode = "400", description = "Invalid image")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/me/avatar")
    public UserProfileResponse uploadAvatar(@RequestParam("file") MultipartFile file) {
        return profileService.uploadAvatar(file);
    }

    @Operation(summary = "Get public profile of a user")
    @ApiResponse(responseCode = "200", description = "Public profile retrieved")
    @ApiResponse(responseCode = "404", description = "User not found")
    @GetMapping("/{id}")
    public PublicProfileResponse getPublicProfile(@PathVariable UUID id) {
        return profileService.getPublicProfile(id);
    }

    @Operation(summary = "List public listings of a user")
    @ApiResponse(responseCode = "200", description = "Listings retrieved")
    @GetMapping("/{id}/listings")
    public Page<Object> getUserListings(@PathVariable UUID id, @PageableDefault Pageable pageable) {
        return userService.listUserListings(id, pageable);
    }

    @Operation(summary = "List public reviews of a user")
    @ApiResponse(responseCode = "200", description = "Reviews retrieved")
    @GetMapping("/{id}/reviews")
    public Page<ReviewResponse> getUserReviews(@PathVariable UUID id, @PageableDefault Pageable pageable) {
        return reviewService.getReviewsByRecipient(id, pageable);
    }
}
