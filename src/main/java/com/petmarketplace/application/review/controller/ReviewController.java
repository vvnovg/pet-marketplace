package com.petmarketplace.application.review.controller;

import com.petmarketplace.application.review.dto.ReviewCreateRequest;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.application.review.service.ReviewService;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Reviews", description = "Reviews and ratings after completed bookings")
@SecurityRequirement(name = "bearer-jwt")
public class ReviewController {

    private final ReviewService reviewService;
    private final UserRepository userRepository;

    @Operation(summary = "Leave a review after a completed booking")
    @ApiResponse(responseCode = "200", description = "Review created")
    @ApiResponse(responseCode = "400", description = "Invalid review data")
    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER', 'SELLER')")
    public ReviewResponse create(@RequestBody @Valid ReviewCreateRequest request) {
        return reviewService.createReview(currentUser(), request);
    }

    @Operation(summary = "List public reviews for a user")
    @ApiResponse(responseCode = "200", description = "Reviews retrieved")
    @GetMapping("/{userId}")
    public Page<ReviewResponse> getByRecipient(@PathVariable UUID userId,
                                                @PageableDefault Pageable pageable) {
        return reviewService.getReviewsByRecipient(userId, pageable);
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            throw new BusinessException("User not authenticated");
        }
        return userRepository.findByEmail(details.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
